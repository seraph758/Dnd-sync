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

        int switchesMask = sp.getInt("switches_mask", 0);
        Log.d(TAG, "🌙 触发勿扰级别同步 -> 当前 Filter: " + interruptionFilter + " | switches_mask: " + switchesMask);

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
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        SharedPreferences sp = getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE);
        // 新增：引入闹钟同步总开关控制
        boolean isAlarmMasterEnabled = sp.getBoolean("alarm_master", true);
        if (!isAlarmMasterEnabled) return;

        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String dismissKey = sp.getString("alarm_dismiss_key", "停止");
        String snoozeKey = sp.getString("alarm_snooze_key", "延后");

        String pkgName = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        String channelId = notification.getChannelId() != null ? notification.getChannelId() : "";
        String category = notification.category != null ? notification.category : "";

        // 1. 只关注目标闹钟应用
        boolean isTargetPackage = pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock");
        if (!isTargetPackage) return;

        // 2. 判断是否为真正的响铃闹钟
        if (!isRealFiringAlarm(sbn)) {
            // 拦截预告、摘要等非响铃通知
            shouldBlockNonFiringNotification(notification, channelId, category);
            return; 
        }

        // 3. 防重复触发
        if (isSameAlarmAsCurrent(sbn)) {
            Log.d(TAG, "🔁 重复响铃通知，跳过处理 key=" + sbn.getKey());
            return;
        }

        // 4. 更新当前闹钟状态
        currentAlarmNotification = sbn;
        dismissPendingIntent = null;
        snoozePendingIntent = null;

        // 5. 提取动作按钮
        extractAlarmActions(notification, dismissKey, snoozeKey);

        // 6. 安全无崩溃兜底处理
        if (dismissPendingIntent == null && notification.actions != null && notification.actions.length > 0) {
            dismissPendingIntent = notification.actions[0].actionIntent;
            Log.d(TAG, "⚠️ 未匹配到关键字，安全兜底提取首个动作按钮作为停止键");
            if (notification.actions.length > 1) {
                snoozePendingIntent = notification.actions[1].actionIntent;
                Log.d(TAG, "⚠️ 安全兜底提取第二个动作按钮作为延后键");
            }
        }

        // 7. 安全检查
        if (dismissPendingIntent == null) {
            Log.w(TAG, "⚠️ 未提取到任何有效的停止或操作按钮，放弃同步 | key=" + sbn.getKey());
            currentAlarmNotification = null;
            return;
        }

        Log.i(TAG, "🚀 检测到真实响铃闹钟！同步至手表 | channel=" + channelId +
                   " | key=" + sbn.getKey() + " | actions=" + notification.actions.length);

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("action", "START_ALARM_UI");
            sendProtocolMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "发送闹钟启动协议失败", e);
        }
    }
    // === [AI_SECURITY_FIREWALL_END: PHONE_NOTIFICATION_SERVICE_ALARM_INTERCEPT] ===

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || currentAlarmNotification == null) return;

        // 只处理当前正在响的闹钟
        if (sbn.getKey().equals(currentAlarmNotification.getKey())) {
            currentAlarmNotification = null;
            dismissPendingIntent = null;
            snoozePendingIntent = null;

            Log.d(TAG, "⏰ 闹钟通知被移除，同步停止手表端 UI");
            sendForceStopToWear();
        }
    }

    /** 强特征判断真正的响铃闹钟 */
        /** 强特征判断真正的响铃闹钟（采用反射彻底绕过旧版 SDK 静态编译报错） */
    private boolean isRealFiringAlarm(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String channelId = n.getChannelId() != null ? n.getChannelId() : "";
        String category = n.category != null ? n.category : "";

        // 默认先给一个高重要性分值
        int currentImportance = NotificationManager.IMPORTANCE_HIGH; 

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 🟢 通过高阶反射动态调用，不留任何方法名硬编码给编译器，彻底击穿静态语法检查
            try {
                java.lang.reflect.Method getImportanceMethod = sbn.getClass().getMethod("getImportance");
                Object result = getImportanceMethod.invoke(sbn);
                if (result instanceof Integer) {
                    currentImportance = (Integer) result;
                }
            } catch (Exception e) {
                Log.w(TAG, "⚠️ 反射获取 sbn.getImportance 失败，启动 Channel 降级检查方案");
                // 降级兜底：从系统的 NotificationManager 提取 Channel 真实权重
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.app.NotificationChannel channel = nm.getNotificationChannel(channelId);
                    if (channel != null) {
                        currentImportance = channel.getImportance();
                    }
                }
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Android 8.0 - 9.0 标准通道获取方式
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                android.app.NotificationChannel channel = nm.getNotificationChannel(channelId);
                if (channel != null) {
                    currentImportance = channel.getImportance();
                }
            }
        } else {
            // Android 8.0 以下的古董版本使用 priority 映射
            currentImportance = n.priority >= Notification.PRIORITY_HIGH ? 
                    NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT;
        }

        // 核心过滤多维特征总线
        boolean isFiringChannel = channelId.toLowerCase().contains("firing");
        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);
        boolean hasKeyFlags = (n.flags & (Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR)) != 0;
        
        // 判定提取出来的最终重要性分值
        boolean highImportance = currentImportance >= NotificationManager.IMPORTANCE_HIGH;
        
        // 顶级关键特征：Google时钟在真响铃时必然携带全屏交互意图（fullscreenIntent）
        boolean hasFullScreen = n.fullScreenIntent != null;

        return (isFiringChannel || isAlarmCategory || hasFullScreen) && hasKeyFlags && highImportance;
    }


    /** 是否应该拦截非响铃通知 */
    private boolean shouldBlockNonFiringNotification(Notification notification, String channelId, String category) {
        if (channelId.toLowerCase().contains("upcoming")) {
            Log.d(TAG, "🛑 拦截 Upcoming 预告通知: " + channelId);
            return true;
        }
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "🛑 拦截 Group Summary 组摘要通知");
            return true;
        }
        if (Notification.CATEGORY_REMINDER.equalsIgnoreCase(category)) {
            Log.d(TAG, "🛑 拦截 Reminder 类别通知");
            return true;
        }

        // 文本过滤
        CharSequence titleChar = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textChar = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = titleChar != null ? titleChar.toString() : "";
        String text = textChar != null ? textChar.toString() : "";

        if (title.contains("即将到来") || text.contains("即将到来") ||
            title.contains("Upcoming") || text.contains("预告")) {
            Log.d(TAG, "🛑 拦截预告文本通知");
            return true;
        }
        return false;
    }

    /** 判断是否为同一个闹钟（防重复） */
    private boolean isSameAlarmAsCurrent(StatusBarNotification newSbn) {
        return currentAlarmNotification != null &&
               currentAlarmNotification.getKey().equals(newSbn.getKey());
    }

    /** 提取停止和延后按钮 */
    private void extractAlarmActions(Notification notification, String dismissKey, String snoozeKey) {
        if (notification.actions == null) return;

        for (Notification.Action action : notification.actions) {
            if (action.title == null) continue;
            String title = action.title.toString().trim();

            if (containsAny(title, dismissKey, "关闭", "停止", "Dismiss", "Cancel")) {
                dismissPendingIntent = action.actionIntent;
                Log.d(TAG, "✅ 提取到停止按钮: " + title);
            }
            if (containsAny(title, snoozeKey, "稍后", "延后", "Snooze", "延期")) {
                snoozePendingIntent = action.actionIntent;
                Log.d(TAG, "✅ 提取到延后按钮: " + title);
            }
        }
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /** 发送停止指令到手表 */
    private void sendForceStopToWear() {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("action", "FORCE_STOP_WEAR_ALARM");
            sendProtocolMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "发送停止协议失败", e);
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
                Log.e(TAG, "投递消息失败", e);
            }
        }).start();
    }
}
