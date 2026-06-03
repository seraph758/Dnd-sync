package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            if (data == null) return;

            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                // 過濾掉手機自己發送的消息
                if ("phone".equalsIgnoreCase(sender)) return;

                // 🎯 🌟【全渠道打通】：處理手錶端全螢幕 UI 發送過來的反向鬧鐘掛斷控制
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 [遠端指揮中心] 收到手錶端全螢幕介面發送的鬧鐘控制動作: " + action);
                    
                    StatusBarNotification sbn = DNDNotificationService.currentAlarmNotification;
                    if (sbn != null && sbn.getNotification() != null) {
                        Notification notification = sbn.getNotification();
                        if (notification.actions != null) {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                            
                            // 依據操作類型載入用戶配置的過濾字典
                            String targetKey = action.equals("dismiss") ? 
                                    prefs.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭,停止") :
                                    prefs.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡,延后");
                            
                            // 尋找對應的 PendingIntent 並發射
                            for (Notification.Action act : notification.actions) {
                                if (act.title != null) {
                                    String title = act.title.toString().toLowerCase();
                                    for (String k : targetKey.split(",")) {
                                        if (!k.isEmpty() && title.contains(k.toLowerCase())) {
                                            try {
                                                Log.d(TAG, "🎯 [遠端執行成功] 成功代點手機本機鬧鐘按鈕: " + act.title);
                                                act.actionIntent.send(); // 🚀 跨設備發射 PendingIntent！
                                            } catch (Exception e) {
                                                Log.e(TAG, "執行手機鬧鐘 Action 失敗", e);
                                            }
                                            return;
                                        }
                                    }
                                }
                            }
                            Log.w(TAG, "⚠️ 未在手機鬧鐘通知中匹配到包含字典關鍵字的按鈕動作。");
                        }
                    } else {
                        Log.w(TAG, "⚠️ 手機端目前無正在緩存響鈴中的有效鬧鐘快照。");
                    }
                }

                // 處理手錶反向勿擾同步（保持原項目相容）
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            isInternalUpdate = true;
                            mNotificationManager.setInterruptionFilter(dndValue);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> isInternalUpdate = false, 2000);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析手錶回傳指令失敗", e);
            }
        }
    }
}
