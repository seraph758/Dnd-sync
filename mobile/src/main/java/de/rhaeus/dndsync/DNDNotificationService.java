package de.rhaeus.dndsync;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
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
    private static final String TAG = "WearSync_PhoneService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;
    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 手机端通知监听中转中心就绪");
    }

    @Override
    public void onListenerDisconnected() {
        Wearable.getMessageClient(this).removeListener(this);
        running = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();

        boolean alarmMaster = sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false);
        if (!alarmMaster) return;

        String allowedPkgs = sharedPreferences.getString("custom_allowed_clock_packages", "com.google.android.deskclock");
        boolean isTargetClock = false;
        for (String p : allowedPkgs.split(",")) {
            if (!p.trim().isEmpty() && pkg.contains(p.trim())) {
                isTargetClock = true;
                break;
            }
        }

        if (isTargetClock) {
            Notification notification = sbn.getNotification();
            if (notification == null) return;

            // 🎯 核心过滤：剔除闹钟通知上的预告性质（只有无法右滑清除的正在响铃状态才拉起Active）
            if ((notification.flags & Notification.FLAG_INSISTENT) != 0 || 
                (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                
                currentAlarmNotification = sbn;
                Log.d(TAG, "🔔 拦截到目标闹钟【真正爆发响铃】 -> 唤醒手表全屏 Active 震动框");
                
                try {
                    JSONObject json = new JSONObject();
                    json.put("sender", "phone");
                    json.put("type", "alarm");
                    json.put("alarmAction", "LAUNCH_WEAR_ALARM_ACTIVITY");
                    json.put("timestamp", System.currentTimeMillis());
                    sendJsonMessage(json.toString());
                } catch (Exception e) {}
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            currentAlarmNotification = null;
            Log.d(TAG, "🔔 手机端闹钟通知已被清除/消退 -> 同步通知关闭手表端 UI");
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                json.put("timestamp", System.currentTimeMillis());
                sendJsonMessage(json.toString());
            } catch (Exception e) {}
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                if ("phone".equalsIgnoreCase(sender)) return; 

                String type = json.optString("type", "");

                // 🎯 远端闹钟指令代理执行
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", ""); 
                    Log.d(TAG, "📥 收到手表反馈代点按键動作: " + action);
                    boolean clicked = handleRemoteAlarmActionClick(action);
                    if (!clicked) {
                        Log.w(TAG, "⚠️ 智能匹配未完美命中动作，执行强制取消兜底");
                        dismissAllClockNotificationsForced();
                    }
                    return;
                }

                // 🎯 远端相机控制中转
                if ("camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 手表向手机申请相机控制动作: " + action);
                    Intent serviceIntent = new Intent(this, CameraService.class);
                    serviceIntent.putExtra("action", action);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "手机接收层分流崩溃", e);
            }
        }
    }

    private boolean handleRemoteAlarmActionClick(String action) {
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications == null) return false;

        String dismissConfig = sharedPreferences.getString("custom_dismiss_action_index", "智能匹配");
        String snoozeConfig = sharedPreferences.getString("custom_snooze_action_index", "智能匹配");
        String allowedPkgs = sharedPreferences.getString("custom_allowed_clock_packages", "com.google.android.deskclock");

        for (StatusBarNotification sbn : activeNotifications) {
            String pkg = sbn.getPackageName();
            boolean isTargetClock = false;
            for (String p : allowedPkgs.split(",")) {
                if (!p.trim().isEmpty() && pkg.contains(p.trim())) {
                    isTargetClock = true;
                    break;
                }
            }

            if (isTargetClock) {
                Notification notification = sbn.getNotification();
                if (notification.actions == null || notification.actions.length == 0) continue;

                // 1. 优先执行数字索引定位点击
                if ("DISMISS".equals(action) && !dismissConfig.contains("智能")) {
                    return performIndexedClick(notification.actions, dismissConfig);
                }
                if ("SNOOZE".equals(action) && !snoozeConfig.contains("智能")) {
                    return performIndexedClick(notification.actions, snoozeConfig);
                }

                // 2. 智能文字关键字匹配
                for (Notification.Action act : notification.actions) {
                    if (act.title == null || act.actionIntent == null) continue;
                    String titleStr = act.title.toString().toLowerCase().trim();

                    if ("DISMISS".equals(action)) {
                        if (titleStr.contains("停") || titleStr.contains("关") || titleStr.contains("dismiss") || titleStr.contains("跳过") || titleStr.contains("关闭")) {
                            return sendActionIntent(act);
                        }
                    }
                    if ("SNOOZE".equals(action)) {
                        if (titleStr.contains("稍") || titleStr.contains("延") || titleStr.contains("snooze") || titleStr.contains("再响") || titleStr.contains("稍后")) {
                            return sendActionIntent(act);
                        }
                    }
                }
                
                // 3. 终极大保底
                return sendActionIntent(notification.actions[0]);
            }
        }
        return false;
    }

    private boolean performIndexedClick(Notification.Action[] actions, String configStr) {
        try {
            int index = Integer.parseInt(configStr.replaceAll("[^0-9]", "")) - 1;
            if (index >= 0 && index < actions.length && actions[index].actionIntent != null) {
                actions[index].actionIntent.send();
                return true;
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean sendActionIntent(Notification.Action action) {
        try {
            action.actionIntent.send();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void dismissAllClockNotificationsForced() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null) return;
            for (StatusBarNotification sbn : activeNotifications) {
                if (sbn.getPackageName().contains("clock") || sbn.getPackageName().contains("alarm")) {
                    cancelNotification(sbn.getKey());
                }
            }
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
            } catch (Exception e) {
                Log.e(TAG, "蓝牙发送异常", e);
            }
        }).start();
    }
}
