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

            if ("phone".equalsIgnoreCase(sender)) return; // 过滤本端回环

            // 1️⃣ 勿扰板块：手表反向控制手机
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "📱 手机成功响应手表反向控制勿扰: " + dndVal);

                        new java.util.Timer().schedule(new java.util.TimerTask() {
                            @Override
                            public void run() { isInternalUpdate = false; }
                        }, 1500);
                    }
                }
                return;
            }

            // 2️⃣ 🎯 闹钟控制板块：打破单向断层，响应手表按键点击事件
            if ("alarm_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ 收到手表端传回的闹钟按键动作: " + action);
                if ("DISMISS".equalsIgnoreCase(action)) {
                    if (PhoneSyncNotificationService.dismissPendingIntent != null) {
                        PhoneSyncNotificationService.dismissPendingIntent.send();
                        Log.d(TAG, "🎯 [自动化成功] 已代用户点击手机通知栏「停止/关闭」按钮");
                    } else {
                        Log.w(TAG, "⚠️ 触发点击失败：手机端暂未捕获到合法的停止 PendingIntent");
                    }
                } else if ("SNOOZE".equalsIgnoreCase(action)) {
                    if (PhoneSyncNotificationService.snoozePendingIntent != null) {
                        PhoneSyncNotificationService.snoozePendingIntent.send();
                        Log.d(TAG, "🎯 [自动化成功] 已代用户点击手机通知栏「延后/稍后提醒」按钮");
                    } else {
                        Log.w(TAG, "⚠️ 触发点击失败：手机端暂未捕获到合法的延后 PendingIntent");
                    }
                }
                return;
            }

                        // 2️⃣ 相機模組控制鏈（手錶反向控制手機服務）
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [相機控制中轉] 收到 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到手錶開啟相機指令，通過 BridgeActivity 進行前台豁免拉起...");
                    Intent bridgeIntent = new Intent(this, PhoneCameraBridgeActivity.class);
                    bridgeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(bridgeIntent);
                }

                else if ("WATCH_READY".equalsIgnoreCase(action)) {
                    // 🤝 完美咬合：全部用 action，手錶 Activity 真正就緒後，中轉通知 CameraService 點火
                    Log.d(TAG, "🤝 [中轉握手] 收到手錶端回傳的 READY 狀態，中轉通知 CameraService 點火");
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("WATCH_READY");
                    startService(svc);
                }  
                else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("STOP_CAMERA");
                    startService(svc);
                } 
                else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 [核心接收] 接收到手表的拍照動作，準備投遞給本地 CameraService");
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("TAKE_PICTURE"); 
                    startService(svc);
                }
                return;
            }



        } catch (Exception e) {
            Log.e(TAG, "手机端处理解析异常", e);
        }
    }
}