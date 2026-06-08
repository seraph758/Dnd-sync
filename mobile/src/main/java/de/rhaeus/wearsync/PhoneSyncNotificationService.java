package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

public class PhoneSyncNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_NotificationService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    public static StatusBarNotification currentAlarmNotification = null;
    public static PendingIntent dismissPendingIntent = null;
    public static PendingIntent snoozePendingIntent = null;

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🌙 勿擾模式變化源自手錶反向修改，攔截防止循環。");
            return;
        }

        // 1. 讀取勿擾總開關
        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        boolean isDndMasterEnabled = sp.getBoolean("dnd_master", true);
        if (!isDndMasterEnabled) {
            Log.d(TAG, "🌙 勿擾總開關關閉，放棄本次同步流程。");
            return;
        }

        // 2. 只有在此時，讀取並載入下層的三個附屬子屬性開關
        boolean isVibrate = sp.getBoolean("dnd_vibrate", false);
        boolean isSleepEnabled = sp.getBoolean("wear_sleep", false);
        boolean isPowerSavingEnabled = sp.getBoolean("wear_power_saving", false);

        Log.d(TAG, "🌙 勿擾變更總線調用 -> Filter: " + interruptionFilter + ", 睡眠連動: " + isSleepEnabled + ", 省電連動: " + isPowerSavingEnabled);

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter);
            json.put("is_vibrate", isVibrate); // 載入下層震動配置

            // 判斷手機是否處於某種有效勿擾狀態
            boolean isActivated = (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                   interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                   interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS);

            if (isActivated) {
                if (isSleepEnabled) {
                    json.put("action", "START_SLEEP_MACRO");
                } else if (isPowerSavingEnabled) {
                    json.put("action", "START_POWER_SAVING_DIRECT");
                } else {
                    json.put("action", "JUST_SYNC_DND");
                }
            } else {
                json.put("action", "STOP_ALL_MODES");
            }

            sendProtocolMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "封裝勿擾打包數據異常", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String pkgName = sbn.getPackageName();

        if (pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock")) {
            Notification notification = sbn.getNotification();
            currentAlarmNotification = sbn;

            if (notification.actions != null && notification.actions.length > 0) {
                dismissPendingIntent = notification.actions[0].actionIntent;
                if (notification.actions.length > 1) {
                    snoozePendingIntent = notification.actions[1].actionIntent;
                }
            }

            Log.d(TAG, "⏰ 監聽到手機端鬧鐘響鈴，發送強彈窗信令");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", "START_ALARM_UI");
                sendProtocolMessage(json.toString());
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String pkgName = sbn.getPackageName();
        String category = sbn.getNotification() != null ? sbn.getNotification().category : "";
        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);

        if (pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock") || isAlarmCategory) {
            currentAlarmNotification = null;
            dismissPendingIntent = null;
            snoozePendingIntent = null;

            Log.d(TAG, "⏰ 手機端鬧鐘已關閉/延後，同步通知解除手錶 UI");
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
                Log.e(TAG, "協議數據投遞至 MessageClient 失敗", e);
            }
        }).start();
    }
}
