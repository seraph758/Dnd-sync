package de.rhaeus.dndsync;

import android.app.Notification;
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
import java.util.Map;
import java.util.Set;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneSource";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // 全域暫存當前正在響鈴的鬧鐘通知物件，供手錶反向消除時使用
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "WearSync 手機端監聽服務已成功連線");
        running = true;
        
        int currentFilter = getCurrentInterruptionFilter();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            sendJsonMessage(buildDynamicJson(currentFilter));
        }
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
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            sendJsonMessage(buildDynamicJson(interruptionFilter));
        }
    }

    // 🎯 核心攔截：全自動鬧鐘過濾與按鈕預翻譯沙盒
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // 【一級總開關】：如果用戶沒有在 UI 打開鬧鐘連動開關，直接丟棄
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) {
            return;
        }

        String packageName = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // 【二級選單：App名單驗證】：讀取用戶勾選的鬧鐘包名集合（預設為空）
        Set<String> allowedPackages = prefs.getStringSet("custom_alarm_allowed_packages", new HashSet<>());
        
        // 為了相容性，如果用戶還沒勾選過任何 App，我們預設放行含有 clock 的包名；一旦勾選了，就嚴格執行勾選名單
        boolean isTargetApp = allowedPackages.isEmpty() ? packageName.contains("clock") : allowedPackages.contains(packageName);

        // 【三級盲篩】：類別必須是 CATEGORY_ALARM 且滿足 App 名單
        if (Notification.CATEGORY_ALARM.equals(notification.category) && isTargetApp) {
            Log.d(TAG, "🔥 成功攔截正牌鬧鐘響鈴！來自: " + packageName);
            currentAlarmNotification = sbn; // 裝載緩存

            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "ringing"); // 標記行為：響鈴中
                json.put("timestamp", System.currentTimeMillis());

                // 🚀 開始執行本地預翻譯：遍歷通知裡的按鈕文字
                if (notification.actions != null) {
                    // 讀取 UI 存入的自定義關鍵字字典，並轉為小寫方便模糊匹配
                    String dismissKeys = prefs.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭").toLowerCase();
                    String snoozeKeys = prefs.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡").toLowerCase();

                    for (Notification.Action action : notification.actions) {
                        if (action.title != null) {
                            String title = action.title.toString().toLowerCase();
                            
                            // 檢查是否命中關閉關鍵字
                            for (String k : dismissKeys.split(",")) {
                                if (!k.isEmpty() && title.contains(k)) {
                                    json.put("hasDismissButton", true);
                                    break;
                                }
                            }
                            // 檢查是否命中暫停/小睡關鍵字
                            for (String k : snoozeKeys.split(",")) {
                                if (!k.isEmpty() && title.contains(k)) {
                                    json.put("hasSnoozeButton", true);
                                    break;
                                }
                            }
                        }
                    }
                }

                // 自動注入 UI 上的其他動態自定義欄位，一併發送
                injectUiCustomSettings(json);
                sendJsonMessage(json.toString());

            } catch (Exception e) {
                Log.e(TAG, "鬧鐘沙盒封裝失敗", e);
            }
        }
    }

    // 🎯 監聽鬧鐘停了：不論是用戶在手機按掉還是自動靜音，只要通知消失，立刻命令手錶「終止震動」
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            Log.d(TAG, "🔊 手機鬧鐘通知已消失（手動關閉或超時），正在通知手錶停震...");
            currentAlarmNotification = null;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "stopped"); // 標記行為：已停止
                sendJsonMessage(json.toString());
            } catch (Exception e) {
                Log.e(TAG, "發送鬧鐘終止訊號失敗", e);
            }
        }
    }

    private String buildDynamicJson(int dndState) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            json.put("timestamp", System.currentTimeMillis());
            injectUiCustomSettings(json);
            return json.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void injectUiCustomSettings(JSONObject json) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            Map<String, ?> allEntries = prefs.getAll();
            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();
                // 擴充機制：UI上只要以 custom_ext_ 開頭的設定，自動打包進 JSON
                if (key.startsWith("custom_ext_")) {
                    json.put(key.replace("custom_ext_", ""), entry.getValue());
                }
            }
            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_response", false));
            json.put("wearVibrate", prefs.getBoolean("wear_vibrate_on_sync", false));
        } catch (Exception e) {
            Log.e(TAG, "動態 UI 數據注入失敗", e);
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
                Log.e(TAG, "MessageClient 發射異常", e);
            }
        }).start();
    }
}
