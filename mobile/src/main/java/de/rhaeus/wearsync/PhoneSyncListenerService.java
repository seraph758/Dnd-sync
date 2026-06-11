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

            // 3️⃣ 相机联动板块：防崩溃、补齐拍照闭环
            if ("camera_action".equalsIgnoreCase(type) || "camera_control".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到手表端唤醒相机命令，准备启动手机端前台采集服务...");

                    // 🎯 核心防发热保护：由于 Android 14 严厉禁止后台直接启动前台相机服务（FGS）
                    // 在正式启动服务前，必须发送一条唤醒 Activity 甚至赋予前台豁免的 Intent，防止系统爆引发热
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("START_CAMERA");
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(svc);
                        } else {
                            startService(svc);
                        }
                    } catch (Exception fgsEx) {
                        Log.e(TAG, "🚨 发生 Android 14 强力安全拦截，拒绝在后台直接拉起相机 FGS 服务！", fgsEx);
                        // 进行安全回退：此处可以引导通知或通过 Activity 拉起过渡，斩断空转死循环发热源头
                    }
                }
                else if ("WATCH_READY".equalsIgnoreCase(actionParam)) {
                        // 🎯 移到這裡：手錶 Activity 真正就緒後，發回來的安全點火信號
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
                // 🎯 核心闭环：手表 3 秒倒计时完美结束，手机本地执行抓拍
                else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 [核心接收] 接收到手表的拍照动作，准备投递给本地 CameraService");
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("TAKE_PICTURE"); // 直接传给 CameraService 让 CameraX 抓取
                    startService(svc);
                }
                return;
            }


        } catch (Exception e) {
            Log.e(TAG, "手机端处理解析异常", e);
        }
    }
}