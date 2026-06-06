package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

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
                Log.d(TAG, "📥 手机通过 MessageClient 收到手表的信信令: " + jsonStr);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");
                String action = json.optString("action", "");

                // 过滤掉手机自身发送的广播广播
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
                if ("alarm_control".equalsIgnoreCase(type)) {
                    Log.d(TAG, "⏰ 收到手表反向控制闹钟请求: " + action);
                    if ("DISMISS".equalsIgnoreCase(action)) {
                        DNDNotificationService.triggerAlarmAction(this, true);
                    } else if ("SNOOZE".equalsIgnoreCase(action)) {
                        DNDNotificationService.triggerAlarmAction(this, false);
                    }
                }

                // 3️⃣ 联动处理手表发来的勿扰状态改变反向请求
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
