package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WatchListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                byte[] data = messageEvent.getData();
                if (data == null) return;

                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);

                String sender = json.optString("sender", "");
                if ("wear".equalsIgnoreCase(sender)) return; // 过滤掉手表本地发出的

                String type = json.optString("type", "");

                // 🎯 1. 处理勿扰及多模式下属联动包
                if ("dnd_sync_packet".equalsIgnoreCase(type) || "dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                    boolean wearSleepLink = json.optBoolean("wearSleepLink", false);
                    boolean wearBatteryLink = json.optBoolean("wearBatteryLink", false);
                    boolean vibrateOnSync = json.optBoolean("vibrateOnSync", false);

                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        Log.d(TAG, "📥 收到手机下发的勿扰同步要求 -> 强制更新系统勿扰");
                        nm.setInterruptionFilter(dndValue);
                    }

                    // 根据手机端下发的配置布尔值执行联动
                    if (wearSleepLink) {
                        Log.d(TAG, " ↳ 手机同步联动：开启手表端睡眠模式");
                        // 依据您的底层API设置对应的睡眠值
                    }
                    if (wearBatteryLink) {
                        Log.d(TAG, " ↳ ↳ 手机同步联动：开启手表端省电模式");
                    }

                    // 🎯 重新更正：只有当手机控制手表且开启了开关，才执行单次震动提示
                    if (vibrateOnSync) {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null && vibrator.hasVibrator()) {
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    }
                    return;
                }

                // 🎯 2. 闹钟处理：拉起 Active / 强制命令自杀
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("LAUNCH_WEAR_ALARM_ACTIVITY".equalsIgnoreCase(alarmAction)) {
                        Intent launchIntent = new Intent(this, WearAlarmActivity.class);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(launchIntent);
                    } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(alarmAction)) {
                        sendBroadcast(new Intent("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"));
                    }
                    return;
                }

                // 🎯 3. 相机处理：指令远程唤醒 / 分流注入图像流
                if ("camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    if ("FORCE_LAUNCH_WEAR_CAMERA".equalsIgnoreCase(action)) {
                        Intent cIntent = new Intent(this, WearCameraActivity.class);
                        cIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(cIntent);
                    }
                    return;
                }

                if ("camera_stream".equalsIgnoreCase(type)) {
                    String base64Str = json.optString("base64Frame", "");
                    if (!base64Str.isEmpty() && WearCameraActivity.isActivityActive) {
                        byte[] imageBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT);
                        Intent frameBroadcast = new Intent("de.rhaeus.dndsync.CAMERA_FRAME_RECEIVED");
                        frameBroadcast.putExtra("raw_jpeg", imageBytes);
                        sendBroadcast(frameBroadcast);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "穿戴端分流监听解析异常", e);
            }
        }
    }
}
