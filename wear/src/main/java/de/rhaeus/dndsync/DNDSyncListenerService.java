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
    private static final long COOLDOWN_MS = 2000; 
    
    // 关键变量：供 DNDNotificationService 读取以防止同步死循环
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
            
            byte dndStatePhone = data[0]; 
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // 状态不同步时才执行
            if (dndStatePhone != (byte) mNotificationManager.getCurrentInterruptionFilter()) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    
                    isInternalUpdate = true; // 开启内部更新锁

                    // 1. 设置就寝模式 (不再调用 DNDSyncAccessService)
                    if (prefs.getBoolean("bedtime_key", true)) {
                        setBedtimeMode(dndStatePhone == 1);
                    }

                    // 2. 设置勿扰模式
                    mNotificationManager.setInterruptionFilter((int) dndStatePhone);
                    
                    // 3. 震动反馈
                    if (prefs.getBoolean("vibrate_key", false)) { vibrate(); }

                    // 2秒后解锁，允许再次同步
                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                }
            }
        }
    }

    /**
     * 通过系统 Secure Settings 直接控制就寝模式，无需模拟点击
     */
    private void setBedtimeMode(boolean enable) {
        int value = enable ? 1 : 0;
        try {
            // 直接修改数据库
            Settings.Secure.putInt(getContentResolver(), "bedtime_mode_enabled", value);
            
            // 发送系统广播以刷新 UI 图标状态
            Intent intent = new Intent("com.google.android.apps.wearable.settings.action.BEDTIME_MODE_SETTINGS_CHANGED");
            intent.putExtra("bedtime_mode_state", value);
            sendBroadcast(intent);
            
            Log.d(TAG, "就寝模式已同步至: " + enable);
        } catch (Exception e) {
            Log.e(TAG, "API 写入失败，请确保已通过 ADB 授予 WRITE_SECURE_SETTINGS 权限");
        }
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
