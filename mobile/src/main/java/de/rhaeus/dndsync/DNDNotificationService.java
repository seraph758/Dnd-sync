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

    @Override
    public void onCreate() {
        super.onCreate();
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
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("dnd_sync_switch", true)) return;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", interruptionFilter);
            json.put("wearSleepModeLink", prefs.getBoolean("wear_sleep_mode_link", true));
            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_link", false));
            json.put("vibrateTipsEnable", prefs.getBoolean("wear_vibrate_on_sync", true));
            json.put("timestamp", System.currentTimeMillis());
            sendJsonMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "推送勿擾封包錯誤", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) return;

        String pkg = sbn.getPackageName();
        String allowedConfig = prefs.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock");
        
        boolean isPackageAllowed = false;
        String[] allowedPkgs = allowedConfig.split(",");
        for (String item : allowedPkgs) {
            if (pkg.equalsIgnoreCase(item.trim())) {
                isPackageAllowed = true;
                break;
            }
        }
        if (!isPackageAllowed) return;

        String eventType = prefs.getString("alarm_event_type_select", "ringing");
        Notification notification = sbn.getNotification();
        
        if ("ringing".equalsIgnoreCase(eventType)) {
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String titleStr = title != null ? title.toString() : "";
            String textStr = text != null ? text.toString() : "";
            if (titleStr.contains("預告") || textStr.contains("即將到期") || titleStr.contains("Upcoming")) {
                Log.d(TAG, "🛑 成功阻斷非標準預告鬧鐘的透傳");
                return; 
            }
        }

        if (Notification.CATEGORY_ALARM.equals(notification.category) || (notification.flags & Notification.FLAG_INSISTENT) != 0) {
            currentAlarmNotification = sbn;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "LAUNCH_WEAR_ALARM_ACTIVITY");
                sendJsonMessage(json.toString());
                Log.d(TAG, "🔥 觸發鬧鐘流硬聯鎖：向手錶發出同步拉起 WearAlarmActivity 廣播");
            } catch (Exception e) {
                Log.e(TAG, "透傳鬧鐘激活失敗", e);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            currentAlarmNotification = null;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
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
                if ("phone".equalsIgnoreCase(json.optString("sender", ""))) return;

                String type = json.optString("type", "");
                
                // 🎯 完美流轉新相機架構：當在通告監聽內收到手錶拉起相機請求，直接安全拉起專門負責鏡頭流的 CameraService
                if ("camera_control".equalsIgnoreCase(type) && "REQUEST_LAUNCH_CAMERA".equalsIgnoreCase(json.optString("action"))) {
                    Log.d(TAG, "📥 通告監聽接收到開啟相機信號 -> 拉起本機獨立 CameraService");
                    try {
                        Intent serviceIntent = new Intent(this, CameraService.class);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "拉起獨立 CameraService 失敗", e);
                    }
                    return;
                }

                // 🎯 完美流轉新相機架構：收到拍照或關閉訊號時，一律轉交給正在運行的 CameraService 執行拍照
                if ("camera_action".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 通告監聽收到相機動作要求 -> 轉發至 CameraService: " + action);
                    try {
                        Intent forwardIntent = new Intent(this, CameraService.class);
                        forwardIntent.putExtra("action", action);
                        startService(forwardIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "轉發動作到 CameraService 失敗", e);
                    }
                    return;
                }

                // 處理手錶回傳的鬧鐘交互代點控制
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 通告監聽收到手錶傳回的精準鬧鐘要求: " + action);
                    
                    if (currentAlarmNotification != null) {
                        Notification.Action[] actions = currentAlarmNotification.getNotification().actions;
                        if (actions != null && actions.length > 0) {
                            SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                            
                            String rule = "關鍵字智能匹配";
                            if ("DISMISS".equalsIgnoreCase(action)) {
                                rule = prefs.getString("custom_dismiss_action_index", "關鍵字智能匹配");
                            } else {
                                rule = prefs.getString("custom_snooze_action_index", "關鍵年智能匹配");
                            }

                            boolean executed = false;

                            if (rule.contains("第 1 個")) {
                                if (actions.length >= 1) { actions[0].actionIntent.send(); executed = true; }
                            } else if (rule.contains("第 2 個")) {
                                if (actions.length >= 2) { actions[1].actionIntent.send(); executed = true; }
                            } else if (rule.contains("第 3 個")) {
                                if (actions.length >= 3) { actions[2].actionIntent.send(); executed = true; }
                            } else if ("自定義輸入關鍵字".equals(rule)) {
                                String userKeyword = "DISMISS".equalsIgnoreCase(action) ? 
                                        prefs.getString("custom_dismiss_keyword_input", "") : prefs.getString("custom_snooze_keyword_input", "");
                                if (userKeyword != null && !userKeyword.trim().isEmpty()) {
                                    for (Notification.Action act : actions) {
                                        String title = act.title.toString().toLowerCase();
                                        if (title.contains(userKeyword.trim().toLowerCase())) {
                                            act.actionIntent.send(); executed = true; break;
                                        }
                                    }
                                }
                            }

                            if (!executed && (rule.equals("關鍵字智能匹配") || "自定義輸入關鍵字".equals(rule))) {
                                for (Notification.Action act : actions) {
                                    String title = act.title.toString().toLowerCase();
                                    if ("DISMISS".equalsIgnoreCase(action)) {
                                        if (title.contains("停") || title.contains("關") || title.contains("消") || title.contains("結") || title.contains("dis")) {
                                            act.actionIntent.send(); executed = true; break;
                                        }
                                    } else {
                                        if (title.contains("延") || title.contains("稍") || title.contains("後") || title.contains("再") || title.contains("snoo")) {
                                            act.actionIntent.send(); executed = true; break;
                                        }
                                    }
                                }
                            }

                            if (!executed) {
                                actions[0].actionIntent.send();
                            }
                        }
                    }
                    
                    try {
                        JSONObject exitJson = new JSONObject();
                        exitJson.put("sender", "phone");
                        exitJson.put("type", "alarm");
                        exitJson.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                        exitJson.put("timestamp", System.currentTimeMillis());
                        sendJsonMessage(exitJson.toString());
                    } catch (Exception e) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "處理控制流異常", e);
            }
        }
    }

    private void sendJsonMessage(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        final byte[] data = jsonStr.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "藍牙發送失敗", e);
            }
        }).start();
    }
}
