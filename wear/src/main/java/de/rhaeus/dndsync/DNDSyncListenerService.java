package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 2000; // API操作极快，冷却可缩短
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(android.os.Looper.getMainLooper());

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecutionTime < COOLDOWN_MS) return;
            lastExecutionTime = currentTime;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            byte[] data = messageEvent.getData();
            if (data == null || data.length == 0) return;
            
            byte dndStatePhone = data[0]; // 假设 1 为开启(DND)，其他为关闭
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            if (dndStatePhone != (byte) mNotificationManager.getCurrentInterruptionFilter()) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    isInternalUpdate = true;

                    // 1. 同步就寝模式 (API 方式)
                    if (prefs.getBoolean("bedtime_key", true)) {
                        setBedtimeMode(dndStatePhone == 1);
                    }

                    // 2. 同步勿扰模式
                    mNotificationManager.setInterruptionFilter((int) dndStatePhone);
                    
                    // 3. 震动反馈
                    if (prefs.getBoolean("vibrate_key", false)) { vibrate(); }

                    // 延迟解锁，防止回环同步
                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                }
            }
        }
    }

    /**
     * 高效设置就寝模式：直接写入系统安全设置并发送广播
     */
    private void setBedtimeMode(boolean enable) {
        int value = enable ? 1 : 0;
        try {
            // 修改设置
            Settings.Secure.putInt(getContentResolver(), "bedtime_mode_enabled", value);
            
            // 发送系统广播通知 UI 刷新（部分 Wear OS 设备需要）
            Intent intent = new Intent("com.google.android.apps.wearable.settings.action.BEDTIME_MODE_SETTINGS_CHANGED");
            intent.putExtra("bedtime_mode_state", value);
            sendBroadcast(intent);
            
            Log.d(TAG, "Bedtime mode set to: " + enable);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set bedtime mode via API", e);
        }
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
