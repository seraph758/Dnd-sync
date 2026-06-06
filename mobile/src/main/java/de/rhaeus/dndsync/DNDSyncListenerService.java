package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
                Log.d(TAG, "📥 手机通过 MessageClient 收到手表的信令: " + jsonStr);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");
                String action = json.optString("action", "");

                // 过滤掉手机自身发送的广播
                if ("phone".equalsIgnoreCase(sender)) return;

                // 1️⃣ 联动控制手机端本地相机前台服务
                if ("camera_control".equalsIgnoreCase(type)) {
                    Intent cameraIntent = new Intent(this, CameraService.class);
                    cameraIntent.setAction(action);
                    cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(cameraIntent);
                    } else {
                        startService(cameraIntent);
                    }
                    Log.d(TAG, "📲 成功调配并启动 CameraService 行为: " + action);
                }

                // 2️⃣ 联动处理手表发来的闹钟反向消除信令 (DISMISS / SNOOZE)
                // 🎯 核心修复：直接在本地闭环消化，不再调用不存在的 DNDNotificationService.triggerAlarmAction
                if ("alarm_control".equalsIgnoreCase(type)) {
                    Log.d(TAG, "⏰ 收到手表反向控制闹钟请求: " + action);
                    boolean isDismiss = "DISMISS".equalsIgnoreCase(action);
                    
                    // 尝试获取 DNDNotificationService 中暂存的活动闹钟通知
                    StatusBarNotification sbn = DNDNotificationService.currentAlarmNotification;
                    if (sbn != null && sbn.getNotification() != null) {
                        if (isDismiss) {
                            // 优先触发删除意图（通常对应 Dismiss）
                            if (sbn.getNotification().deleteIntent != null) {
                                sbn.getNotification().deleteIntent.send();
                                Log.d(TAG, "⏰ 成功触发手机闹钟通知的 deleteIntent (Dismiss)");
                            } else if (sbn.getNotification().contentIntent != null) {
                                sbn.getNotification().contentIntent.send();
                                Log.d(TAG, "⏰ 备用尝试触发手机闹钟通知的 contentIntent");
                            }
                        } else {
                            // Snooze 延迟：如果有操作按钮，尝试触发第一个 Action（通常系统闹钟第一个是延迟）
                            if (sbn.getNotification().actions != null && sbn.getNotification().actions.length > 0) {
                                PendingIntent actionIntent = sbn.getNotification().actions[0].actionIntent;
                                if (actionIntent != null) {
                                    actionIntent.send();
                                    Log.d(TAG, "⏰ 成功触发手机闹钟通知的第一个 ActionIntent (Snooze)");
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "⏰ 未能定位到当前活动的手机闹钟通知实例，无法执行反向消除");
                    }
                }

                // 3️⃣ 联动处理手表发来的勿擾状态改变反向请求
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dnd_profile_value", -1);
                    if (dndValue != -1) {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null && nm.getCurrentInterruptionFilter() != dndValue) {
                            Log.d(TAG, "🌙 手表反向更新手机勿扰状态至: " + dndValue);
                            nm.setInterruptionFilter(dndValue);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "MessageClient 信令解析中转失败", e);
            }
        }
    }
}
