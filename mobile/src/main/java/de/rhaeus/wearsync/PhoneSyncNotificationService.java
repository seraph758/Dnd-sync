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

    // === [AI_SECURITY_FIREWALL: PHONE_NOTIFICATION_SERVICE_DND_BROADCAST] ===
    // 勿扰拦截发送模块：直接读取实时保存的分数下发，绝无延迟计算
    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        if (PhoneSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "🌙 勿扰信号源于手表反向变更，拦截不再回发，规避死循环。");
            return;
        }

        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        boolean isDndMasterEnabled = sp.getBoolean("dnd_master", true);
        if (!isDndMasterEnabled) return;

        // 秒读本地现成的 Linux 状态分数，直接打包
        int switchesMask = sp.getInt("switches_mask", 0);
        Log.d(TAG, "🌙 触发勿扰级别同步 -> 当前 Filter 硬件级别: " + interruptionFilter + " | 组合状态总分数: " + switchesMask);

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter);
            json.put("switches_mask", switchesMask); 

            sendProtocolMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "分发勿扰协议异常", e);
        }
    }
    // === [AI_SECURITY_FIREWALL_END: PHONE_NOTIFICATION_SERVICE_DND_BROADCAST] ===

    // === [AI_SECURITY_FIREWALL: PHONE_NOTIFICATION_SERVICE_ALARM_INTERCEPT] ===
    // ⏰ 闹钟核心抓取拦截总线（基于系统闹钟通知分类识别真响铃，支持关键字映射）
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String dismissKey = sp.getString("alarm_dismiss_key", "停止");
        String snoozeKey = sp.getString("alarm_snooze_key", "延后");
        
        String pkgName = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        String category = notification.category != null ? notification.category : "";

        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);
        boolean isTargetPkg = pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock");

        if (isAlarmCategory || isTargetPkg) {
            // 系统预告/即将到来通知特征卡死，完美规避提前假响铃
            if (Notification.CATEGORY_REMINDER.equalsIgnoreCase(category)) {
                Log.d(TAG, "🛑 拦截预告通知: 识别到 category 属于提醒类特征。");
                return;
            }

            CharSequence titleChar = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textChar = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String title = titleChar != null ? titleChar.toString() : "";
            String text = textChar != null ? textChar.toString() : "";

            if (title.contains("即将到来") || text.contains("即将到来") || text.contains("预告")) {
                Log.d(TAG, "🛑 拦截预告通知: 匹配到明显的日程预告文本。");
                return;
            }

            // 锁定真闹钟实体结构
            currentAlarmNotification = sbn;
            dismissPendingIntent = null;
            snoozePendingIntent = null;

            if (notification.actions != null) {
                for (Notification.Action action : notification.actions) {
                    if (action.title == null) continue;
                    String actionTitle = action.title.toString();
                    
                    if (actionTitle.contains(dismissKey) || actionTitle.contains("关闭")) {
                        dismissPendingIntent = action.actionIntent;
                        Log.d(TAG, "🎯 匹配到自定义停止动作端点: " + actionTitle);
                    }
                    if (actionTitle.contains(snoozeKey) || actionTitle.contains("稍后")) {
                        snoozePendingIntent = action.actionIntent;
                        Log.d(TAG, "🎯 匹配到自定义延后动作端点: " + actionTitle);
                    }
                }
            }

            // 兜底提取器
            if (dismissPendingIntent == null && notification.actions != null && notification.actions.length > 0) {
                dismissPendingIntent = notification.actions[0].actionIntent;
                if (notification.actions.length > 1) {
                    snoozePendingIntent = notification.actions[1].actionIntent;
                }
            }

            Log.d(TAG, "🚀 闹钟检测通行，推送手表端唤醒全屏响铃UI交互层...");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", "START_ALARM_UI");
                sendProtocolMessage(json.toString());
            } catch (Exception ignored) {}
        }
    }
    // === [AI_SECURITY_FIREWALL_END: PHONE_NOTIFICATION_SERVICE_ALARM_INTERCEPT] ===

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

            Log.d(TAG, "⏰ 手机闹钟通知撤销，同步释放远端手表交互 UI");
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
            } catch (Exception e) { Log.e(TAG, "投递故障", e); }
        }).start();
    }
}
