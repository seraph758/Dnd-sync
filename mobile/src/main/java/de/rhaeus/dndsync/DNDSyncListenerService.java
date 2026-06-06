package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            if (data == null) return;

            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                if ("phone".equalsIgnoreCase(sender)) return;

                // 1. 相机控制流转发
                if ("camera_control".equalsIgnoreCase(type) || "camera_action".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 监听到手表端相机指令: " + action + " -> 正在同步流转至 CameraService");
                    Intent cameraIntent = new Intent(this, CameraService.class);
                    cameraIntent.setAction(action);
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(cameraIntent);
                        } else {
                            startService(cameraIntent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "启动相机前台服务失败", e);
                    }
                    return;
                }

                // 2. 闹钟交互代点控制中心
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 远端代点中心收到手表端闹钟动作要求: " + action);
                    
                    StatusBarNotification sbn = DNDNotificationService.currentAlarmNotification;
                    boolean executed = false;

                    if (sbn != null && sbn.getNotification() != null) {
                        Notification notification = sbn.getNotification();
                        if (notification.actions != null && notification.actions.length > 0) {
                            SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                            Notification.Action[] actions = notification.actions;

                            // 【核心规则 1】：最高优先级——自定义关键字输入匹配
                            String userKeyword = "DISMISS".equalsIgnoreCase(action) ? 
                                    prefs.getString("custom_dismiss_keyword_input", "") : prefs.getString("custom_snooze_keyword_input", "");
                            
                            if (userKeyword != null && !userKeyword.trim().isEmpty()) {
                                String cleanKeyword = userKeyword.trim().toLowerCase();
                                for (Notification.Action act : actions) {
                                    if (act.title != null) {
                                        String title = act.title.toString().toLowerCase();
                                        if (title.contains(cleanKeyword)) {
                                            Log.d(TAG, "🎯 [自定义关键字命中] 成功代点用户指定按钮: " + act.title);
                                            act.actionIntent.send();
                                            executed = true;
                                            break;
                                        }
                                    }
                                }
                            }

                            // 【核心规则 2】：次高优先级——智能模糊匹配（去除位置兜底）
                            if (!executed) {
                                for (Notification.Action act : actions) {
                                    if (act.title != null) {
                                        String title = act.title.toString().toLowerCase();
                                        if ("DISMISS".equalsIgnoreCase(action)) {
                                            // 智能匹配停止/关闭/挂断等核心词
                                            if (title.contains("停") || title.contains("关") || title.contains("消") || 
                                                title.contains("结") || title.contains("闭") || title.contains("dis") || title.contains("off")) {
                                                Log.d(TAG, "🎯 [智能模糊命中] 成功匹配停止按钮: " + act.title);
                                                act.actionIntent.send();
                                                executed = true;
                                                break;
                                            }
                                        } else if ("SNOOZE".equalsIgnoreCase(action)) {
                                            // 智能匹配延后/稍后/小睡核心词
                                            if (title.contains("延") || title.contains("稍") || title.contains("后") || 
                                                title.contains("再") || title.contains("snoo")) {
                                                Log.d(TAG, "🎯 [智能模糊命中] 成功匹配延后按钮: " + act.title);
                                                act.actionIntent.send();
                                                executed = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            if (!executed) {
                                Log.w(TAG, "⚠️ 闹钟通知中未匹配到符合自定义或智能规则的关键字按钮。");
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ 手机端当前快照中无正在响铃的有效闹钟。");
                    }

                    // 【核心改动】：不管点击成功与否，一旦执行完毕，立刻无条件向手表发送强退信号，确保手表App关闭！
                    sendExitSignalToWear();
                    return;
                }

                // 3. 勿扰反向同步
                if ("dnd".equalsIgnoreCase(type)) {
                    SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    if (!prefs.getBoolean("dnd_sync_switch", true)) return;

                    int dndValue = json.optInt("dndValue", 1);
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            Log.d(TAG, "📥 收到手表反向勿扰请求 -> 更新手机系统勿扰状态");
                            mNotificationManager.setInterruptionFilter(dndValue);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析手表回传数据失败", e);
            }
        }
    }

    private void sendExitSignalToWear() {
        new Thread(() -> {
            try {
                JSONObject exitJson = new JSONObject();
                exitJson.put("sender", "phone");
                exitJson.put("type", "alarm");
                exitJson.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                exitJson.put("timestamp", System.currentTimeMillis());
                byte[] data = exitJson.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                    Log.d(TAG, "📤 [强制退出] 已向手表端强发 FORCE_STOP_WEAR_ALARM 退出信号");
                }
            } catch (Exception e) {
                Log.e(TAG, "向手表发送退出信号失败", e);
            }
        }).start();
    }
}
