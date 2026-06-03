package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
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
import java.util.List;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_PhoneSource";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;
    private int lastSentDndState = -1;

    // 🟢 聯動優化：手機端省電模式監聽器（綁定到全局 Context 確保生命週期安全）
    private final BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                if (prefs.getBoolean("phone_power_save_link", true)) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        boolean isPhonePowerSaveOn = pm.isPowerSaveMode();
                        Log.d(TAG, "🔌 [手機省電監聽] 狀態變更為: " + isPhonePowerSaveOn + "，發送至手錶...");
                        sendPowerSaveJson(isPhonePowerSaveOn);
                    }
                }
            }
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        
        // 使用全局 ApplicationContext 註冊省電廣播，防止服務重啟時掉線
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        getApplicationContext().registerReceiver(powerSaveReceiver, filter);

        syncCurrentDndState();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        try {
            getApplicationContext().unregisterReceiver(powerSaveReceiver);
        } catch (Exception e) {}
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (interruptionFilter == lastSentDndState) return; 
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_switch", true)) {
            lastSentDndState = interruptionFilter;
            sendJsonMessage(buildDndJson(interruptionFilter));
        }
    }

    public void syncCurrentDndState() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                int currentFilter = nm.getCurrentInterruptionFilter();
                lastSentDndState = currentFilter;
                sendJsonMessage(buildDndJson(currentFilter));
            }
        } catch (Exception e) {}
    }

    // 🔴 鬧鐘優化：完美相容「鎖屏全屏響鈴」與「亮屏使用時的頂部浮窗提示」
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        String packageName = sbn.getPackageName().toLowerCase();
        boolean isClockApp = packageName.contains("clock") || packageName.contains("alarm") || Notification.CATEGORY_ALARM.equals(notification.category);
        if (!isClockApp) return;

        // 🌟 步驟 1：提取文字內容，啟動全文本關鍵字排他沙盒（強力撲殺提前預告通知）
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
            Log.d(TAG, "🛑 [沙盒攔截] 檢測到鬧鐘【提前預告通知】，非真實響鈴，已安全過濾。");
            return;
        }

        // 🌟 步驟 2：核心判定鐵證。無論是全屏響鈴還是亮屏浮窗，真實響鈴的鬧鐘必須帶有「關閉/稍後」等交互按鈕
        if (notification.actions == null || notification.actions.length == 0) {
            Log.d(TAG, "🛑 [沙盒攔截] 該通知不含交互按鈕，判定為常駐狀態欄提示，已過濾。");
            return;
        }

        // 🌟 步驟 3：排除滿足以上條件但優先級過低的靜態通知
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                android.app.NotificationChannel channel = nm.getNotificationChannel(notification.getChannelId());
                if (channel != null && channel.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                    Log.d(TAG, "🛑 [沙盒攔截] 通知渠道重要性不足，過濾。");
                    return;
                }
            }
        }

        Log.d(TAG, "🔥 [鬧鐘確認] 觸發真實響鈴（支援全屏與亮屏浮窗）! 向手錶發送同步信號。");
        currentAlarmNotification = sbn;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("alarmAction", "ringing");
            sendJsonMessage(json.toString());
        } catch (Exception e) {}
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            Log.d(TAG, "🛑 手動掛斷/小睡，通知消失，終止手錶端動作。");
            currentAlarmNotification = null;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "stopped");
                sendJsonMessage(json.toString());
            } catch (Exception e) {}
        }
    }

    private String buildDndJson(int dndState) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            return json.toString();
        } catch (Exception e) { return ""; }
    }

    private void sendPowerSaveJson(boolean isPhonePowerSaveOn) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "phone_power_status"); 
            json.put("isPhonePowerSaveOn", isPhonePowerSaveOn);
            sendJsonMessage(json.toString());
        } catch (Exception e) {}
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
            } catch (Exception e) {}
        }).start();
    }
}
