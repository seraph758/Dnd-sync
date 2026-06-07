package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

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

            if ("wear".equalsIgnoreCase(sender)) return; // 拦截自发自收

            // 🔥 核心逻辑：凡是来自手机的指令，均使用 Thread 异步强制亮屏，防止熄屏状态下Activity和宏失效
            lightUpScreenAsync();

            // 1️⃣ 勿扰与模式联动板块
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.setInterruptionFilter(dndVal);
                    }

                    // 开启震动
                    if (json.optBoolean("wear_vibrate_toggle", true)) {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null) {
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    }

                    // 判断手机是开启还是关闭勿扰 (INTERRUPTION_FILTER_ALL 为 1，代表关闭勿扰/恢复正常)
                    boolean isDndOn = (dndVal != NotificationManager.INTERRUPTION_FILTER_ALL);
                    DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                    
                    if (accessService != null) {
                        if (isDndOn) {
                            // 手机打开勿扰 -> 手表联动打开睡眠/省电
                            if (json.optBoolean("wear_sleep_toggle", true)) accessService.triggerBedtimeMacro(true);
                            if (json.optBoolean("wear_power_toggle", true)) accessService.triggerPowerSavingMacro(true);
                        } else {
                            // 手机关闭勿扰 -> 手表无条件联动关闭睡眠/省电！
                            if (json.optBoolean("wear_sleep_toggle", true)) accessService.triggerBedtimeMacro(false);
                            if (json.optBoolean("wear_power_toggle", true)) accessService.triggerPowerSavingMacro(false);
                        }
                    }
                }
                return;
            }

            // 2️⃣ 闹钟模块响应
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }

            // 3️⃣ 相机唤醒模块响应
            if ("camera_action".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(camIntent);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手表解析数据核心异常", e);
        }
    }

    private void lightUpScreenAsync() {
        new Thread(() -> {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    PowerManager.WakeLock wakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                            "WearSync:WakeLockAsync"
                    );
                    wakeLock.acquire(3000); // 持续提亮3秒
                    Log.d(TAG, "⚡ Thread 异步强制唤醒手表屏幕成功");
                }
            } catch (Exception e) {
                Log.e(TAG, "强制亮屏发生异常", e);
            }
        }).start();
    }
}
