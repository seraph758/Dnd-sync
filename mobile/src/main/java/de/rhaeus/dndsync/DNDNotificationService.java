package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_NotificationService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static StatusBarNotification currentAlarmNotification = null;

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        
        // 如果勿擾模式的變化源自於手錶端反向控制鎖，則直接攔截回發，防止死循環
        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🌙 勿擾模式變化源自手錶反向修改，防止乒乓死循環，攔截不再回發。");
            return;
        }

        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        if (!sp.getBoolean("dnd_master", true)) return;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter);
            json.put("wear_sleep_toggle", sp.getBoolean("wear_sleep_toggle", false));
            json.put("wear_power_saving_toggle", sp.getBoolean("wear_power_saving_toggle", false));

            sendProtocolMessage(json.toString());
            Log.d(TAG, "🌙 成功將手機端原生勿擾狀態 [" + interruptionFilter + "] 同步給手錶藍牙端");
        } catch (Exception e) {
            Log.e(TAG, "封裝勿擾同步協議報文失敗", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;

        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");

        // 🎯 智慧相容判定：讀取 Android 官方標準鬧鐘分類標籤
        String category = sbn.getNotification() != null ? sbn.getNotification().category : "";
        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);

        // 條件滿足：包名完全一致 OR 包名包含時鐘關鍵字 OR 具備官方鬧鐘標籤
        if (sbn.getPackageName().equalsIgnoreCase(targetPkg) || sbn.getPackageName().contains("deskclock") || isAlarmCategory) {
            if (currentAlarmNotification == null) {
                currentAlarmNotification = sbn;
                Log.d(TAG, "⏰ 偵測到合法的系統鬧鐘響鈴通知，準備同步手錶 UI");
                try {
                    JSONObject json = new JSONObject();
                    json.put("sender", "phone");
                    json.put("type", "alarm");
                    json.put("action", "START_ALARM_UI");
                    sendProtocolMessage(json.toString()); // 精準單次投遞，絕不重複轟炸
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;
        
        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");

        String category = sbn.getNotification() != null ? sbn.getNotification().category : "";
        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);

        if (sbn.getPackageName().equalsIgnoreCase(targetPkg) || sbn.getPackageName().contains("deskclock") || isAlarmCategory) {
            currentAlarmNotification = null;
            Log.d(TAG, "⏰ 手機端鬧鐘已關閉/延後，通知同步解除手錶 UI");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", "FORCE_STOP_WEAR_ALARM"); // 預期解除手錶端響鈴介面
                sendProtocolMessage(json.toString());
            } catch (Exception ignored) {}
        }
    }

    private void sendProtocolMessage(String payload) {
        final byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "MessageClient 協議報文投遞失敗", e);
            }
        }).start();
    }
}
