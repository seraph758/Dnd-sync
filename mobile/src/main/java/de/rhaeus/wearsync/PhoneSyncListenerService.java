package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("phone".equalsIgnoreCase(sender)) return; // 过滤回环

            // 1️⃣ 勿扰板块：手表反向控制手机
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        isInternalUpdate = true; // 加反向锁，防止双端死循环
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "📱 手机成功响应手表反向控制勿扰: " + dndVal);
                        
                        // 1.5秒后自动解锁
                       new java.util.Timer().schedule(new java.util.TimerTask() {
                          @Override
                            public void run() { isInternalUpdate = false; }
                        }, 1500);
                    }
                }
                return;
            }

            // 2️⃣ 相机联动板块：拉起手机端 Camera 采集服务或执行拍照
            if ("camera_action".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到拉起命令，正在启动手机后台前台采集服务...");
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("START_CAMERA");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("STOP_CAMERA");
                    startService(svc);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手机端处理解析异常", e);
        }
    }
}
