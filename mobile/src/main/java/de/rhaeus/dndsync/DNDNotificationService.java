package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;
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
    private int lastSentDndState = -1;

    private BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                if (prefs.getBoolean("phone_power_save_link", false)) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        boolean isPowerSaveOn = pm.isPowerSaveMode();
                        Log.d(TAG, "🔌 偵測到手機省電模式變更: " + isPowerSaveOn + "，開始同步手錶...");
                        sendPowerSaveJson(isPowerSaveOn);
                    }
                }
            }
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "WearSync 守護服務已連線");
        running = true;
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        registerReceiver(powerSaveReceiver, filter);

        syncCurrentDndState();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        try {
            unregisterReceiver(powerSaveReceiver);
        } catch (Exception e) {
            // 忽略未註冊
        }
        try {
            requestRebind(new ComponentName(this, DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "服務重綁失敗", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (DNDSyncListenerService.isInternalUpdate) return;
        if (interruptionFilter == lastSentDndState) return; 
        
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

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) return;

        String packageName = sbn.getPackageName().toLowerCase();
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Set<String> allowedPackages = prefs.getStringSet("custom_alarm_allowed_packages", new HashSet<>());
        boolean isAlarmApp = false;
        if (!allowedPackages.isEmpty()) {
            isAlarmApp = allowedPackages.contains(sbn.getPackageName());
        } else {
            isAlarmApp = packageName.contains("clock") 
                    || packageName.contains("deskclock") 
                    || packageName.contains("alarm")
                    || Notification.CATEGORY_ALARM.equals(notification.category);
        }

        if (!isAlarmApp) return;

        // 🎯 🌟【精準防誤觸核心】：提取標題與內容，全力撲殺即將響鈴預告通知
        String title = "";
        String text = "";
        if (notification.extras != null) {
            CharSequence titleChar = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textChar = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (titleChar != null) title = titleChar.toString().toLowerCase();
            if (textChar != null) text = textChar.toString().toLowerCase();
        }

        if (title.contains("即将") || title.contains("upcoming") || title.contains("提前") || title.contains("预告")
            || text.contains("即将") || text.contains("upcoming") || text.contains("提前") || text.contains("预告")) {
            Log.d(TAG, "🛑 [沙盒攔截] 檢測到鬧鐘即將響鈴的【預告通知】，已安全過濾，手錶不觸發震動。");
            return;
        }

        // 🎯 🌟【核心保險】：響鈴中的鬧鐘必然帶有可交互動作按鈕，若無則為靜態提示，直接攔截
        if (notification.actions == null || notification.actions.length == 0) {
            Log.d(TAG, "🛑 [沙盒攔截] 該鬧鐘不含動作按鈕，判定為靜態狀態欄，忽略。");
            return;
        }

        Log.d(TAG, "🔥 [鬧鐘確認] 成功捕獲到真實【響鈴中】的鬧鐘事件: " + sbn.getPackageName());
        currentAlarmNotification = sbn;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("alarmAction", "ringing");
            json.put("timestamp", System.currentTimeMillis());

            String dismissKeys = prefs.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭").toLowerCase();
            String snoozeKeys = prefs.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡").toLowerCase();

            for (Notification.Action action : notification.actions) {
                if (action.title != null) {
                    String actTitle = action.title.toString().toLowerCase();
                    for (String k : dismissKeys.split(",")) {
                        if (!k.isEmpty() && actTitle.contains(k)) {
                            json.put("hasDismissButton", true);
                            break;
                        }
                    }
                    for (String k : snoozeKeys.split(",")) {
                        if (!k.isEmpty() && actTitle.contains(k)) {
                            json.put("hasSnoozeButton", true);
                            break;
                        }
                    }
                }
            }

            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_response", false));
            json.put("wearVibrate", prefs.getBoolean("wear_vibrate_on_sync", true));
            sendJsonMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "鬧鐘數據打包失敗", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            Log.d(TAG, "🛑 偵測到手機鬧鐘移除/被掛斷，向手錶發送終止訊號。");
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
            json.put("dndValue", dndState);
            json.put("timestamp", System.currentTimeMillis());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_response", false));
            json.put("wearVibrate", prefs.getBoolean("wear_vibrate_on_sync", true));
            return json.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void sendPowerSaveJson(boolean isPhonePowerSaveOn) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "phone_power_status"); 
            json.put("isPhonePowerSaveOn", isPhonePowerSaveOn);
            json.put("timestamp", System.currentTimeMillis());
            sendJsonMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "省電狀態 JSON 打包失敗", e);
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
