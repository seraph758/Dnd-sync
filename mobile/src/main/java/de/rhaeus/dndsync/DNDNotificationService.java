package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneSource";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;
    
    // 引入本地勿擾狀態鎖，防止重複發送相同狀態導致手錶隨機震動
    private int lastSentDndState = -1;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "WearSync 守護服務已連線");
        running = true;
        
        // 初始化時發送一次真實的勿擾狀態
        syncCurrentDndState();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        try {
            requestRebind(new ComponentName(this, DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "服務重綁失敗", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (DNDSyncListenerService.isInternalUpdate) return;
        
        // 🔒 【修復問題2、3】狀態鎖：如果勿擾狀態沒變，絕對不重複發送
        if (interruptionFilter == lastSentDndState) {
            return; 
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_switch", true)) {
            lastSentDndState = interruptionFilter;
            sendJsonMessage(buildDynamicJson(interruptionFilter));
        }
    }

    public void syncCurrentDndState() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                int currentFilter = nm.getCurrentInterruptionFilter();
                lastSentDndState = currentFilter;
                sendJsonMessage(buildDynamicJson(currentFilter));
            }
        } catch (Exception e) {
            Log.e(TAG, "獲取當前勿擾失敗", e);
        }
    }

    // 🎯 【修復問題4】全方位放寬鬧鐘攔截網，精準捕獲谷歌時鐘
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // 一級總開關攔截
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) {
            return;
        }

        String packageName = sbn.getPackageName().toLowerCase();
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // 讀取用戶自選 App 列表
        Set<String> allowedPackages = prefs.getStringSet("custom_alarm_allowed_packages", new HashSet<>());
        
        // 🚀 【終極相容過濾】滿足以下任意條件，即認定為鬧鐘：
        // 1. 用戶在UI名單裡勾選了這個包名
        // 2. 用戶沒勾選，但包名包含谷歌時鐘、三星、小米等標準時鐘關鍵字
        // 3. 通知的類別明確是 CATEGORY_ALARM
        boolean isAlarmApp = false;
        if (!allowedPackages.isEmpty()) {
            isAlarmApp = allowedPackages.contains(sbn.getPackageName());
        } else {
            isAlarmApp = packageName.contains("clock") 
                    || packageName.contains("deskclock") 
                    || packageName.contains("alarm")
                    || Notification.CATEGORY_ALARM.equals(notification.category);
        }

        // 如果不是鬧鐘 App，直接原地 return 丟棄！【徹底解決問題2的後台高頻刷屏】
        if (!isAlarmApp) {
            return; 
        }

        Log.d(TAG, "🔥 [鬧鐘確認] 成功捕獲目標鬧鐘事件: " + sbn.getPackageName());
        currentAlarmNotification = sbn;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("alarmAction", "ringing");
            json.put("timestamp", System.currentTimeMillis());

            if (notification.actions != null) {
                String dismissKeys = prefs.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭").toLowerCase();
                String snoozeKeys = prefs.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡").toLowerCase();

                for (Notification.Action action : notification.actions) {
                    if (action.title != null) {
                        String title = action.title.toString().toLowerCase();
                        for (String k : dismissKeys.split(",")) {
                            if (!k.isEmpty() && title.contains(k)) {
                                json.put("hasDismissButton", true);
                                break;
                            }
                        }
                        for (String k : snoozeKeys.split(",")) {
                            if (!k.isEmpty() && title.contains(k)) {
                                json.put("hasSnoozeButton", true);
                                break;
                            }
                        }
                    }
                }
            }

            // 注入其他 UI 屬性並發送
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
            json.put("wearPowerSave", p.getBoolean("wear_power_save_response", false));
            json.put("wearVibrate", p.getBoolean("wear_vibrate_on_sync", true));
            
            sendJsonMessage(json.toString());

        } catch (Exception e) {
            Log.e(TAG, "鬧鐘打包失敗", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            Log.d(TAG, "🔊 鬧鐘通知消失，通知手錶解脫震動。");
            currentAlarmNotification = null;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "stopped");
                sendJsonMessage(json.toString());
            } catch (Exception e) {
                Log.e(TAG, "發送停止訊號失敗", e);
            }
        }
    }

    private String buildDynamicJson(int dndState) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState); // 🚀 【修復問題1】此處傳送真實的系統勿擾濾波值（如INTERRUPTION_FILTER_NONE=3）
            json.put("timestamp", System.currentTimeMillis());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_response", false));
            json.put("wearVibrate", prefs.getBoolean("wear_vibrate_on_sync", true));
            return json.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void sendJsonMessage(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        final byte[] data = jsonStr.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes == null || nodes.isEmpty()) return;
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "發送異常", e);
            }
        }).start();
    }
}
