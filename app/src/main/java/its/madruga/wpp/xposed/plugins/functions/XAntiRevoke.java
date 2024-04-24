package its.madruga.wpp.xposed.plugins.functions;

import static its.madruga.wpp.xposed.plugins.core.XMain.mApp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import its.madruga.wpp.core.databases.DelMessageStore;
import its.madruga.wpp.core.databases.MessageStore;
import its.madruga.wpp.xposed.Unobfuscator;
import its.madruga.wpp.xposed.UnobfuscatorCache;
import its.madruga.wpp.xposed.models.XHookBase;
import its.madruga.wpp.xposed.plugins.core.DesignUtils;
import its.madruga.wpp.xposed.plugins.core.Utils;
import its.madruga.wpp.xposed.plugins.core.WppCore;
import its.madruga.wpp.xposed.plugins.core.XMain;

public class XAntiRevoke extends XHookBase {

    private static final HashMap<String, HashSet<String>> messageRevokedMap = new HashMap<>();
    private static SharedPreferences mShared;
    private static Field fieldMessageKey;
    private static Field getFieldIdMessage;

    public XAntiRevoke(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        mShared = mApp.getSharedPreferences(mApp.getPackageName() + "_mdgwa_preferences", Context.MODE_PRIVATE);
        migrateMessages();

        var antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(loader);
        logDebug(Unobfuscator.getMethodDescriptor(antiRevokeMessageMethod));

        var classThreadMessage = Unobfuscator.loadThreadMessageClass(loader);
        logDebug("Class: " + classThreadMessage);

        fieldMessageKey = Unobfuscator.loadMessageKeyField(loader);
        logDebug(Unobfuscator.getFieldDescriptor(fieldMessageKey));

        getFieldIdMessage = Unobfuscator.loadSetEditMessageField(loader);
        logDebug(Unobfuscator.getFieldDescriptor(getFieldIdMessage));


        var bubbleMethod = Unobfuscator.loadAntiRevokeBubbleMethod(loader);
        logDebug(Unobfuscator.getMethodDescriptor(bubbleMethod));

        var unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(loader);
        logDebug(Unobfuscator.getMethodDescriptor(unknownStatusPlaybackMethod));

        var statusPlaybackField = Unobfuscator.loadStatusPlaybackViewField(loader);
        logDebug(Unobfuscator.getFieldDescriptor(statusPlaybackField));

        XposedBridge.hookMethod(antiRevokeMessageMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                var objMessage = classThreadMessage.cast(param.args[0]);
                var fieldMessageDetails = XposedHelpers.getObjectField(objMessage, fieldMessageKey.getName());
                var fieldIsFromMe = XposedHelpers.getBooleanField(fieldMessageDetails, "A02");
                if (!fieldIsFromMe) {
                    if (antiRevoke(objMessage) != 0)
                        param.setResult(true);
                }
            }
        });


        XposedBridge.hookMethod(bubbleMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var objMessage = param.args[2];
                var dateTextView = (TextView) param.args[1];
                isMRevoked(objMessage, dateTextView, "antirevoke");
            }
        });

        XposedBridge.hookMethod(unknownStatusPlaybackMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var obj = param.args[1];
                var objMessage = param.args[0];
                Object objView = statusPlaybackField.get(obj);
                Field[] textViews = Arrays.stream(statusPlaybackField.getType().getDeclaredFields()).filter(f -> f.getType() == TextView.class).toArray(Field[]::new);
                if (textViews == null) {
                    log("Could not find TextView");
                    return;
                }
                @SuppressLint("DiscouragedApi")
                int dateId = XMain.mApp.getResources().getIdentifier("date", "id", "com.whatsapp");
                for (Field textView : textViews) {
                    TextView textView1 = (TextView) XposedHelpers.getObjectField(objView, textView.getName());
                    if (textView1 == null || textView1.getId() == dateId) {
                        isMRevoked(objMessage, textView1, "antirevokestatus");
                        break;
                    }
                }
            }
        });

    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Anti Revoke";
    }

    public static Drawable scaleImage(Resources resources, Drawable image, float scaleFactor) {
        if (!(image instanceof BitmapDrawable)) {
            return image;
        }
        Bitmap b = ((BitmapDrawable) image).getBitmap();
        int sizeX = Math.round(image.getIntrinsicWidth() * scaleFactor);
        int sizeY = Math.round(image.getIntrinsicHeight() * scaleFactor);
        Bitmap bitmapResized = Bitmap.createScaledBitmap(b, sizeX, sizeY, false);
        return new BitmapDrawable(resources, bitmapResized);
    }

    private static void saveRevokedMessage(String authorJid, String messageKey, Object objMessage) {
        HashSet<String> messages = getRevokedMessages(objMessage);
        messages.add(messageKey);
        DelMessageStore.getInstance(mApp).insertMessage(authorJid, messageKey);
    }

    private static HashSet<String> getRevokedMessages(Object objMessage) {
        String jid = stripJID(getJidAuthor(objMessage));
        if (messageRevokedMap.containsKey(jid)) {
            return messageRevokedMap.get(jid);
        }
        var messages = DelMessageStore.getInstance(mApp).getMessagesByJid(jid);
        if (messages == null) messages = new HashSet<>();
        messageRevokedMap.put(jid, messages);
        return messages;
    }

    public static String stripJID(String str) {
        try {
            return (str.contains("@g.us") || str.contains("@s.whatsapp.net") || str.contains("@broadcast")) ? str.substring(0, str.indexOf("@")) : str;
        } catch (Exception e) {
            XposedBridge.log(e.getMessage());
            return str;
        }
    }

    public static String getJidAuthor(Object objMessage) {
        Object fieldMessageDetails = XposedHelpers.getObjectField(objMessage, fieldMessageKey.getName());
        Object fieldMessageAuthorJid = XposedHelpers.getObjectField(fieldMessageDetails, "A00");
        if (fieldMessageAuthorJid == null) return "";
        else return WppCore.getRawString(fieldMessageAuthorJid);
    }


    private void isMRevoked(Object objMessage, TextView dateTextView, String antirevokeType) {
        if (dateTextView == null) return;
        var fieldMessageDetails = XposedHelpers.getObjectField(objMessage, fieldMessageKey.getName());
        var messageKey = (String) XposedHelpers.getObjectField(fieldMessageDetails, "A01");
        var messageRevokedList = getRevokedMessages(objMessage);
        var id = XposedHelpers.getLongField(objMessage, getFieldIdMessage.getName());
        String keyOrig;
        if (messageRevokedList.contains(messageKey) || ((keyOrig = MessageStore.getOriginalMessageKey(id)) != null && messageRevokedList.contains(keyOrig))) {
            var antirevokeValue = prefs.getInt(antirevokeType, 0);
            if (antirevokeValue == 1) {
                // Text
                var newTextData = UnobfuscatorCache.getInstance().getString("messagedeleted") + " | " + dateTextView.getText();
                dateTextView.setText(newTextData);
            } else if (antirevokeValue == 2) {
                // Icon
                var icon = DesignUtils.getDrawableByName("msg_status_client_revoked");
                var drawable = scaleImage(mApp.getResources(), icon, 0.7f);
                drawable.setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP));
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
                dateTextView.setCompoundDrawablePadding(5);
            }
        } else {
            dateTextView.setCompoundDrawables(null, null, null, null);
            var revokeNotice = UnobfuscatorCache.getInstance().getString("messagedeleted") + " | ";
            var dateText = dateTextView.getText().toString();
            if (dateText.contains(revokeNotice)) {
                dateTextView.setText(dateText.replace(revokeNotice, ""));
            }
        }
    }

    private void migrateMessages() {
        Map<String, ?> map = mShared.getAll();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.contains("revoked")) continue;
            var jid = key.replace("_revoked", "");
            var msg = (String) entry.getValue();
            var messages = Utils.StringToStringArray(msg);
            if (messages != null) {
                for (var message : messages) {
                    DelMessageStore.getInstance(mApp).insertMessage(jid, message);
                }
            }
            mShared.edit().remove(key).apply();
        }
    }

    private int antiRevoke(Object objMessage) {
        var messageKey = (String) XposedHelpers.getObjectField(objMessage, "A01");
        var stripJID = stripJID(getJidAuthor(objMessage));
        var revokeboolean = stripJID.equals("status") ? prefs.getInt("antirevokestatus", 0) : prefs.getInt("antirevoke", 0);
        if (revokeboolean == 0) return revokeboolean;

        var messageRevokedList = getRevokedMessages(objMessage);
        if (!messageRevokedList.contains(messageKey)) {
            try {
                AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                    saveRevokedMessage(stripJID, messageKey, objMessage);
                    try {
                        var mConversation = WppCore.getCurrenConversation();
                        if (mConversation != null && WppCore.stripJID(WppCore.getCurrentRawJID()).equals(stripJID)) {
                            mConversation.runOnUiThread(() -> {
                                if (mConversation.hasWindowFocus()) {
                                    mConversation.startActivity(mConversation.getIntent());
                                    mConversation.overridePendingTransition(0, 0);
                                } else {
                                    mConversation.recreate();
                                }
                            });
                        }
                    } catch (Exception e) {
                        XposedBridge.log(e.getMessage());
                    }
                });
            } catch (Exception e) {
                XposedBridge.log(e.getMessage());
            }
        }
        return revokeboolean;
    }

}
