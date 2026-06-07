package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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

            if ("wear".equalsIgnoreCase(sender)) return; // 杜绝自收

            // 1️⃣ 勿扰板块：完全无条件跟随时同步
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.setInterruptionFilter(dndVal);

                    // 震动处理逻辑
                    if (json.optBoolean("wear_vibrate_toggle", true)) {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null) {
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    }

                    boolean dndEnabled = (dndVal != NotificationManager.INTERRUPTION_FILTER_ALL);
                    DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();

                    if (accessService != null) {
                        // 如果开启了睡眠联动并且当前进入勿扰状态，触发巨集下拉自动化操作
                        if (json.optBoolean("wear_sleep_toggle", true) && dndEnabled) {
                            accessService.triggerBedtimeMacro();
                        }
                        // 如果开启了省电联动
                        if (json.optBoolean("wear_power_toggle", false) && dndEnabled) {
                            accessService.triggerPowerSavingMacro();
                        }
                    }
                }
                return;
            }

            // 2️⃣ 闹钟板块：拉起全屏或强制退出
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

            // 3️⃣ 相机板块：被动被手机拉起 UI
            if ("camera_control".equalsIgnoreCase(type) && "START_CAMERA_UI".equalsIgnoreCase(action)) {
                Intent camIntent = new Intent(this, WearCameraActivity.class);
                camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(camIntent);
            }

        } catch (Exception e) {
            Log.e(TAG, "手表接收解析底层信令失败", e);
        }
    }
}
