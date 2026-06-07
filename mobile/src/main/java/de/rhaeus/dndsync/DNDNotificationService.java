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
        
        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        if (!sp.getBoolean("dnd_master", true)) return;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter);
            json.put("wear_sleep_toggle", sp.getBoolean("wear_sleep_toggle", false));
            json.put("wear_power_toggle", sp.getBoolean("wear_power_toggle", false));
            json.put("wear_vibrate_toggle", sp.getBoolean("wear_vibrate_toggle", true));
            
            Log.d(TAG, "🌙 手机端勿扰触发变化，广播协议包发送至手表: " + json.toString());
            sendProtocolMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "构建勿扰同步协议异常", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;

        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        if (!sp.getBoolean("alarm_master", true)) return;

        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");
        String currentPkg = sbn.getPackageName();

        Notification notification = sbn.getNotification();
        
        // 🎯 核心修复：强力判定闹钟。除了匹配包名外，还要检测通知类别是否为 CATEGORY_ALARM 或者携带全屏意图
        boolean isAlarmCategory = (notification != null && Notification.CATEGORY_ALARM.equals(notification.category));
        boolean isTargetAlarm = currentPkg.equalsIgnoreCase(targetPkg) || currentPkg.contains("deskclock") || isAlarmCategory;

        if (isTargetAlarm) {
            currentAlarmNotification = sbn;
            Log.d(TAG, "⏰ 💥 【核心成功拦截】手机端检测到系统闹钟正在响铃! 包名: " + currentPkg);
            
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", "START_ALARM_UI"); // 指令：通知手表直接启动全屏 WearAlarmActivity
                json.put("timestamp", System.currentTimeMillis());
                
                sendProtocolMessage(json.toString());
                Log.d(TAG, "⏰ 成功向手表总线投递 START_ALARM_UI 响铃指令");
            } catch (Exception e) {
                Log.e(TAG, "发送闹钟启动协议异常", e);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;
        
        SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
        String targetPkg = sp.getString("alarm_pkg", "com.google.android.deskclock");

        if (sbn.getPackageName().equalsIgnoreCase(targetPkg) || sbn.getPackageName().contains("deskclock")) {
            currentAlarmNotification = null;
            Log.d(TAG, "⏰ 手机端闹钟已关闭/延后，通知同步解除");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("action", "FORCE_STOP_WEAR_ALARM"); // 默认停止和延后都会清空UI
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
