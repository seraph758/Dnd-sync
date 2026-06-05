package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;
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
    private static final String TAG = "WearSync_PhoneSource";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 手機端同步發射端服務掛載成功");
        
        // 挂载成功后主动触发一次同步
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            pushDndAndPowerStatusToWear(manager.getCurrentInterruptionFilter(), false);
        }
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
        Log.d(TAG, "📱 偵測到手機系統勿擾模式變更: " + interruptionFilter + "，啟動穿戴發射鏈條");
        pushDndAndPowerStatusToWear(interruptionFilter, true);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        // 如果用戶關閉了鬧鐘同步，直接返回
        if (!prefs.getBoolean("alarm_sync_switch", true)) return;

        String currentPackage = sbn.getPackageName();
        String alarmType = prefs.getString("alarm_type_select", "all");

        boolean isAlarm = false;
        if ("system".equals(alarmType)) {
            // 僅限制主流官方內建時鐘
            if (currentPackage.contains("android.deskclock") || 
                currentPackage.contains("com.sec.android.app.clockpackage") || 
                currentPackage.contains("com.google.android.deskclock")) {
                isAlarm = true;
            }
        } else {
            // 全域攔截：包含任何帶有 clock 或 deskclock 標記的包名
            if (currentPackage.contains("clock") || currentPackage.contains("deskclock")) {
                isAlarm = true;
            }
        }

        Notification notification = sbn.getNotification();
        if (isAlarm || Notification.CATEGORY_ALARM.equals(notification.category) || (notification.flags & Notification.FLAG_INSISTENT) != 0) {
            currentAlarmNotification = sbn;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "ringing");
                sendJsonMessage(json.toString());
                Log.d(TAG, "🔥 成功攔截並向手錶傳遞鬧鐘響鈴狀態！來自: " + currentPackage);
            } catch (Exception e) {
                Log.e(TAG, "發送鬧鐘狀態失敗", e);
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
                json.put("alarmAction", "stopped");
                sendJsonMessage(json.toString());
                Log.d(TAG, "🛑 手機鬧鐘已關閉，通知手錶停止震動");
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
                if ("camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                            if (audioManager != null) {
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA));
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA));
                                Log.d(TAG, "📸 快門物理鍵注入成功");
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "處理手錶反向回傳失敗", e);
            }
        }
    }

    // 🎯 精確修正：對齊 MainFragment 設定中的 Key 名稱，實現雙向同步突破
    public void pushDndAndPowerStatusToWear(int dndState, boolean isRealTimeSync) {
        try {
            SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
            // 🎯 對齊 UI 開關：dnd_sync_switch 和 sleep_mode_sync_switch
            boolean dndSyncAllowed = prefs.getBoolean("dnd_sync_switch", true);
            boolean wearPowerSave = prefs.getBoolean("sleep_mode_sync_switch", true);
            boolean wearVibrate = prefs.getBoolean("vibrate_on_sync_switch", true);

            if (!dndSyncAllowed) {
                Log.d(TAG, "⚠️ 勿擾同步開關已關閉，跳過向手錶發射狀態");
                return;
            }

            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            json.put("wearPowerSave", wearPowerSave); // 這邊會傳遞給手錶，手錶端以此來決定是否自動啟動睡眠模式
            json.put("wearVibrate", wearVibrate);
            json.put("isRealTimeSync", isRealTimeSync);
            json.put("timestamp", System.currentTimeMillis());

            sendJsonMessage(json.toString());
            Log.d(TAG, "⚡ 成功向手錶發射同步封包: " + json.toString());
        } catch (Exception e) {
            Log.e(TAG, "構建勿擾同步封包失敗", e);
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
