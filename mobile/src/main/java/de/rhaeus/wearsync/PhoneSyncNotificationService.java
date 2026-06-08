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

    // 靜態變量保持引用
    public static StatusBarNotification currentAlarmNotification = null;

    // 🎯 用於保存手機通知欄上對應按鈕的觸發觸點（核心自動化命脈）
    public static PendingIntent dismissPendingIntent = null;
    public static PendingIntent snoozePendingIntent = null;

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);

        if (PhoneSyncListenerService.isInternalUpdate) {
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
            json.put("wear_sleep_toggle", sp.getBoolean("wear_sleep_toggle", false));
            json.put("wear_power_saving_toggle", sp.getBoolean("wear_power_saving_toggle", false));

            sendProtocolMessage(json.toString());
            Log.d(TAG, "🌙 成功将手机端原生勿扰状态 [" + interruptionFilter + "] 同步给手表蓝牙端");
        } catch (Exception e) {
            Log.e(TAG, "封装勿扰同步协议报文失败", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;

        // 1. 讀取 UI 設定的 3 個自定義配置
        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String dismissKey = sp.getString("alarm_dismiss_key", "停止");
        String snoozeKey = sp.getString("alarm_snooze_key", "延后");

        String pkgName = sbn.getPackageName();
        String category = sbn.getNotification() != null ? sbn.getNotification().category : "";
        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);

        // 🎯 核心攔截策略：必須匹配設定的包名，或者具備官方鬧鐘標籤
        if (pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock") || isAlarmCategory) {

            // 排除可以被滑動消除的預告通知
            boolean canUserClearIt = (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) == 0 
                    && (sbn.getNotification().flags & Notification.FLAG_NO_CLEAR) == 0;
            if (canUserClearIt && !isAlarmCategory) {
                return; 
            }

            if (currentAlarmNotification == null) {
                currentAlarmNotification = sbn;
                Log.d(TAG, "⏰ 侦测到合法的「真正响铃」系统闹钟，开始解析动作按钮");

                // 🎯 核心解析：掃描通知欄上的按鈕文字，精準抓取「停止」和「延後」的觸發意圖
                Notification notification = sbn.getNotification();
                if (notification != null && notification.actions != null) {
                    for (Notification.Action action : notification.actions) {
                        CharSequence actionTitle = action.title;
                        if (actionTitle != null) {
                            String titleStr = actionTitle.toString();
                            // 匹配 UI 設定的停止關鍵字
                            if (titleStr.contains(dismissKey)) {
                                dismissPendingIntent = action.actionIntent;
                                Log.d(TAG, "🎯 成功捕获手机端「" + dismissKey + "」按钮的 PendingIntent");
                            }
                            // 匹配 UI 設定的延後關鍵字
                            else if (titleStr.contains(snoozeKey)) {
                                snoozePendingIntent = action.actionIntent;
                                Log.d(TAG, "🎯 成功捕获手机端「" + snoozeKey + "」按钮的 PendingIntent");
                            }
                        }
                    }
                }

                // 通知手錶拉起響鈴界面
                try {
                    JSONObject json = new JSONObject();
                    json.put("sender", "phone");
                    json.put("type", "alarm");
                    json.put("action", "START_ALARM_UI");
                    sendProtocolMessage(json.toString());
                    Log.d(TAG, "⏰ 已向手表端发送 START_ALARM_UI 唤醒信令");
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
        String pkgName = sbn.getPackageName();
        String category = sbn.getNotification() != null ? sbn.getNotification().category : "";
        boolean isAlarmCategory = Notification.CATEGORY_ALARM.equalsIgnoreCase(category);

        if (pkgName.equalsIgnoreCase(targetPkg) || pkgName.contains("deskclock") || isAlarmCategory) {
            // 清空本地靜態緩存
            currentAlarmNotification = null;
            dismissPendingIntent = null;
            snoozePendingIntent = null;

            Log.d(TAG, "⏰ 手机端闹钟已关闭/延后，通知同步解除手表 UI");
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