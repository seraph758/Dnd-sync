package de.rhaeus.dndsync;

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
        // 如果受到了反向修改锁限制，直接跳过不发送给手表
        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🌙 勿扰模式变化源自手表反向修改，防止乒乓死循环，拦截不再回发。");
            return;
        }

        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        if (!sp.getBoolean("dnd_master", true)) return;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter);
            json.put("wear_sleep_toggle", sp.getBoolean("wear_sleep", true));
            json.put("wear_power_toggle", sp.getBoolean("wear_power", false));
            json.put("wear_vibrate_toggle", sp.getBoolean("wear_vibrate", true));

            sendProtocolMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "构建勿扰同步数据异常", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null || sbn.getNotification() == null) return;

        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        if (!sp.getBoolean("alarm_master", true)) return;

        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        if (sbn.getPackageName().equalsIgnoreCase(targetPkg)) {
            // 过滤预告通知，只拦截 Ongoing 状态的真正响铃通知
            boolean isOngoing = (sbn.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
            if (isOngoing) {
                currentAlarmNotification = sbn;
                try {
                    JSONObject json = new JSONObject();
                    json.put("sender", "phone");
                    json.put("type", "alarm");
                    json.put("action", "START_ALARM_UI");
                    sendProtocolMessage(json.toString());
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

        if (sbn.getPackageName().equalsIgnoreCase(targetPkg)) {
            currentAlarmNotification = null;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", "FORCE_STOP_WEAR_ALARM");
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
                Log.e(TAG, "MessageClient 协议报文投递失败", e);
            }
        }).start();
    }
}
