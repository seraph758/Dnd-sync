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

    // 静态变量保持引用，供后台强力闹钟代理解析及远程接管调用
    public static StatusBarNotification currentAlarmNotification = null;
    public static PendingIntent dismissPendingIntent = null;
    public static PendingIntent snoozePendingIntent = null;

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🌙 勿扰模式变化源自手表反向修改，拦截防止乒乓死循环。");
            return;
        }

        // 1. 读取勿扰总开关
        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        boolean isDndMasterEnabled = sp.getBoolean("dnd_master", true);
        if (!isDndMasterEnabled) {
            Log.d(TAG, "🌙 勿扰总开关关闭，放弃本次状态同步流程。");
            return;
        }

        // 2. 动态加载勿扰下的三个下层附属子开关值
        boolean isVibrate = sp.getBoolean("dnd_vibrate", false);
        boolean isSleepEnabled = sp.getBoolean("wear_sleep", false);
        boolean isPowerSavingEnabled = sp.getBoolean("wear_power_saving", false);

        Log.d(TAG, "🌙 勿扰变更总线调用 -> Filter: " + interruptionFilter + ", 睡眠连动: " + isSleepEnabled + ", 省电连动: " + isPowerSavingEnabled);

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter);
            json.put("is_vibrate", isVibrate); // 压入下层震动配置

            // 判断手机是否处于有效勿扰激活状态
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
            Log.e(TAG, "封装勿扰打包数据异常", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String pkgName = sbn.getPackageName();

        // 🎯 完美留存核心：截获闹钟服务推送并剥离控制触点
        if (pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock")) {
            Notification notification = sbn.getNotification();
            currentAlarmNotification = sbn;

            if (notification.actions != null && notification.actions.length > 0) {
                dismissPendingIntent = notification.actions[0].actionIntent;
                if (notification.actions.length > 1) {
                    snoozePendingIntent = notification.actions[1].actionIntent;
                }
            }

            Log.d(TAG, "⏰ 监听到了手机闹钟响铃，成功拦截代理触点，下发强弹窗信令给手表");
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

            Log.d(TAG, "⏰ 手机端闹钟已手动关闭/延后，通知同步解除手表响铃 UI");
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
                Log.e(TAG, "协议数据投递至 MessageClient 失败", e);
            }
        }).start();
    }
}
