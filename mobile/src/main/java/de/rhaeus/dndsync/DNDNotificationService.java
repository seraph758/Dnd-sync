package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDNotificationService extends NotificationListenerService implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSync_PhoneService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;
    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 手機端接收解析服務掛載就緒");
    }

    @Override
    public void onListenerDisconnected() {
        Wearable.getMessageClient(this).removeListener(this);
        running = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();

        boolean alarmMaster = sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false);
        if (!alarmMaster) return;

        String allowedPkgs = sharedPreferences.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock");
        boolean isTargetClock = false;
        for (String p : allowedPkgs.split(",")) {
            if (!p.trim().isEmpty() && pkg.contains(p.trim())) {
                isTargetClock = true;
                break;
            }
        }

        if (isTargetClock) {
            String eventType = sharedPreferences.getString("alarm_event_type_select", "ringing");
            Notification notification = sbn.getNotification();
            
            boolean shouldTrigger = false;
            if ("all_events".equalsIgnoreCase(eventType)) {
                shouldTrigger = true;
            } else {
                if ((notification.flags & Notification.FLAG_INSISTENT) != 0 || 
                    (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                    shouldTrigger = true;
                }
            }

            if (shouldTrigger) {
                currentAlarmNotification = sbn;
                Log.d(TAG, "🔥 觸發鬧鐘流硬聯鎖：向手錶發出同步拉起 WearAlarmActivity 廣播");
                
                try {
                    JSONObject json = new JSONObject();
                    json.put("sender", "phone");
                    json.put("type", "alarm");
                    json.put("alarmAction", "LAUNCH_WEAR_ALARM_ACTIVITY");
                    json.put("timestamp", System.currentTimeMillis());
                    sendJsonMessage(json.toString());
                } catch (Exception e) {}
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            currentAlarmNotification = null;
            Log.d(TAG, "🔔 手機端鬧鐘通知已消失 -> 向手錶發送強制關閉介面訊號");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                json.put("timestamp", System.currentTimeMillis());
                sendJsonMessage(json.toString());
            } catch (Exception e) {}
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                if ("phone".equalsIgnoreCase(sender)) return;

                String type = json.optString("type", "");

                // 接收手錶端按鈕點擊，執行精準代點或強制消除
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", ""); // "DISMISS" 或 "SNOOZE"
                    Log.d(TAG, "📥 遠端代點中心收到手錶端鬧鐘動作要求: " + action);

                    boolean clickSuccess = handleRemoteAlarmActionClick(action);
                    
                    if (clickSuccess) {
                        Log.d(TAG, "✅ 成功透過通知欄按鈕執行代點動作");
                    } else {
                        Log.w(TAG, "⚠️ 字典規則未命中或代點失敗，啟用終極物理抹除防止死循環");
                        dismissAllClockNotificationsForced();
                    }
                    return;
                }

                // 轉發相機指令
                if ("camera_action".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Intent forwardIntent = new Intent(this, CameraService.class);
                    forwardIntent.putExtra("action", action);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(forwardIntent);
                    } else {
                        startService(forwardIntent);
                    }
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "通告監聽中轉相機指令異常", e);
            }
        }
    }

    private boolean handleRemoteAlarmActionClick(String action) {
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications == null) return false;

        String dismissConfig = sharedPreferences.getString("custom_dismiss_action_index", "關鍵字智能匹配");
        String snoozeConfig = sharedPreferences.getString("custom_snooze_action_index", "關鍵字智能匹配");
        String customDismissKeyword = sharedPreferences.getString("custom_dismiss_keyword_input", "").toLowerCase().trim();
        String customSnoozeKeyword = sharedPreferences.getString("custom_snooze_keyword_input", "").toLowerCase().trim();

        String allowedPkgs = sharedPreferences.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock");

        for (StatusBarNotification sbn : activeNotifications) {
            String pkg = sbn.getPackageName();
            boolean isTargetClock = false;
            for (String p : allowedPkgs.split(",")) {
                if (!p.trim().isEmpty() && pkg.contains(p.trim())) {
                    isTargetClock = true;
                    break;
                }
            }

            if (isTargetClock) {
                Notification notification = sbn.getNotification();
                if (notification.actions == null || notification.actions.length == 0) continue;

                if ("DISMISS".equals(action) && dismissConfig.contains("第")) {
                    return performIndexedClick(notification.actions, dismissConfig);
                }
                if ("SNOOZE".equals(action) && snoozeConfig.contains("第")) {
                    return performIndexedClick(notification.actions, snoozeConfig);
                }

                for (Notification.Action act : notification.actions) {
                    if (act.title == null || act.actionIntent == null) continue;
                    String titleStr = act.title.toString().toLowerCase().trim();

                    if ("DISMISS".equals(action)) {
                        if ("自定義輸入關鍵字".equals(dismissConfig) && !customDismissKeyword.isEmpty() && titleStr.contains(customDismissKeyword)) {
                            return sendActionIntent(act);
                        }
                        if ("關鍵字智能匹配".equals(dismissConfig) && (titleStr.contains("停") || titleStr.contains("關") || titleStr.contains("关") || titleStr.contains("dismiss") || titleStr.contains("跳过"))) {
                            return sendActionIntent(act);
                        }
                    }

                    if ("SNOOZE".equals(action)) {
                        if ("自定義輸入關鍵字".equals(snoozeConfig) && !customSnoozeKeyword.isEmpty() && titleStr.contains(customSnoozeKeyword)) {
                            return sendActionIntent(act);
                        }
                        if ("關鍵字智能匹配".equals(snoozeConfig) && (titleStr.contains("稍") || titleStr.contains("延") || titleStr.contains("snooze") || titleStr.contains("再响"))) {
                            return sendActionIntent(act);
                        }
                    }
                }
                
                if ("DISMISS".equals(action) && "關鍵字智能匹配".equals(dismissConfig)) {
                    Log.w(TAG, "⚠️ 字典規則未命中，執行首個按鈕兜底發射");
                    return sendActionIntent(notification.actions[0]);
                }
            }
        }
        return false;
    }

    private boolean performIndexedClick(Notification.Action[] actions, String configStr) {
        try {
            int index = 0;
            if (configStr.contains("1")) index = 0;
            else if (configStr.contains("2")) index = 1;
            else if (configStr.contains("3")) index = 2;

            if (actions.length > index && actions[index].actionIntent != null) {
                return sendActionIntent(actions[index]);
            }
        } catch (Exception e) {
            Log.e(TAG, "索引點擊異常", e);
        }
        return false;
    }

    private boolean sendActionIntent(Notification.Action action) {
        try {
            action.actionIntent.send();
            Log.d(TAG, "🎯 成功觸發手機通知按鈕點擊: " + action.title);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "發射 ActionIntent 失敗", e);
            return false;
        }
    }

    public void dismissAllClockNotificationsForced() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null) return;
            String allowedPkgs = sharedPreferences.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock");

            for (StatusBarNotification sbn : activeNotifications) {
                String pkg = sbn.getPackageName();
                boolean isTargetClock = false;
                for (String p : allowedPkgs.split(",")) {
                    if (!p.trim().isEmpty() && pkg.contains(p.trim())) {
                        isTargetClock = true;
                        break;
                    }
                }
                if (isTargetClock) {
                    cancelNotification(sbn.getKey());
                    Log.d(TAG, "💥 規則完全未命中，已在手機端強制 cancelNotification 摧毀通知");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "暴力清除鬧鐘通知異常", e);
        }
    }

    private void sendJsonMessage(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        final byte[] data = jsonStr.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    // 🎯 這裡已修正為 node.getId() 確保編繹通過
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "藍牙發送失敗", e);
            }
        }).start();
    }
}
