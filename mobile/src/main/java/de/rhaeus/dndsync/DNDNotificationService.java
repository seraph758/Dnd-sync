package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
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
    private static final String TAG = "WearSync_PhoneService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 互联发射监听端服务挂载成功");
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
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("dnd_sync_switch", true)) return;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", interruptionFilter);
            json.put("wearSleepModeLink", prefs.getBoolean("wear_sleep_mode_link", true));
            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_link", false));
            json.put("vibrateTipsEnable", prefs.getBoolean("wear_vibrate_on_sync", true));
            json.put("timestamp", System.currentTimeMillis());
            sendJsonMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "推送勿扰封包错误", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) return;

        String pkg = sbn.getPackageName();
        String allowedConfig = prefs.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock");
        
        boolean isPackageAllowed = false;
        String[] allowedPkgs = allowedConfig.split(",");
        for (String item : allowedPkgs) {
            if (pkg.equalsIgnoreCase(item.trim())) {
                isPackageAllowed = true;
                break;
            }
        }
        if (!isPackageAllowed) return;

        String eventType = prefs.getString("alarm_event_type_select", "ringing");
        Notification notification = sbn.getNotification();
        
        if ("ringing".equalsIgnoreCase(eventType)) {
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String titleStr = title != null ? title.toString() : "";
            String textStr = text != null ? text.toString() : "";
            if (titleStr.contains("预告") || textStr.contains("即将到期") || titleStr.contains("Upcoming")) {
                Log.d(TAG, "🛑 过滤拦截策略生效：成功阻断非标准预告闹钟的透传");
                return; 
            }
        }

        if (Notification.CATEGORY_ALARM.equals(notification.category) || (notification.flags & Notification.FLAG_INSISTENT) != 0) {
            currentAlarmNotification = sbn;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "LAUNCH_WEAR_ALARM_ACTIVITY");
                sendJsonMessage(json.toString());
                Log.d(TAG, "🔥 触发闹钟流硬联锁：向手表发出同步拉起 WearAlarmActivity 广播");
            } catch (Exception e) {
                Log.e(TAG, "透传闹钟激活失败", e);
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
                json.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
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
                if ("phone".equalsIgnoreCase(json.optString("sender", ""))) return;

                String type = json.optString("type", "");
                
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", ""); // 手表传回来的指令："DISMISS" 或 "SNOOZE"
                    Log.d(TAG, "📥 收到手表传回的精准控制要求: " + action);
                    
                    if (currentAlarmNotification != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Notification.Action[] actions = currentAlarmNotification.getNotification().actions;
                        if (actions != null && actions.length > 0) {
                            boolean executed = false;
                            for (Notification.Action act : actions) {
                                String title = act.title.toString().toLowerCase();
                                // 🎯 精准判别：停止、清除、关闭、消警
                                if ("dismiss".equalsIgnoreCase(action)) {
                                    if (title.contains("停") || title.contains("关") || title.contains("消") || title.contains("解") || title.contains("dis")) {
                                        act.actionIntent.send();
                                        Log.d(TAG, "⚡ 成功定位手机闹钟【停止】按钮并执行点击");
                                        executed = true;
                                        break;
                                    }
                                }
                                // 🎯 精准判别：延后、稍后提醒、再响
                                if ("snooze".equalsIgnoreCase(action)) {
                                    if (title.contains("延") || title.contains("稍") || title.contains("后") || title.contains("再") || title.contains("snoo")) {
                                        act.actionIntent.send();
                                        Log.d(TAG, "⚡ 成功定位手机闹钟【延后】按钮并执行点击");
                                        executed = true;
                                        break;
                                    }
                                }
                            }
                            // 如果以上关键字都没有匹配成功，则执行兜底逻辑（点击第一个按钮）
                            if (!executed) {
                                actions[0].actionIntent.send();
                                Log.d(TAG, "⚠️ 未匹配到特定动作关键字，默认执行第一项清除意图");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "处理手表端反向协议包失败", e);
            }
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
                Log.e(TAG, "穿戴无线传输失败", e);
            }
        }).start();
    }
}
