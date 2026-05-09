package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
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

            // 只有狀態不同時才更新
            if (dndStatePhone != (byte) mNotificationManager.getCurrentInterruptionFilter()) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    isInternalUpdate = true;

                    // 執行「組合拳」：同步就寢相關參數
                    if (prefs.getBoolean("bedtime_key", true)) {
                        applyBedtimeCombo(dndStatePhone == 1);
                    }

                    // 同步勿擾模式
                    mNotificationManager.setInterruptionFilter((int) dndStatePhone);
                    
                    if (prefs.getBoolean("vibrate_key", false)) { vibrate(); }

                    // 延時解鎖，防止回環同步
                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                }
            }
        }
    }

    /**
     * 組合拳模式：直接修改系統數據庫
     * 這種方式不需要亮屏，不需要模擬點擊，反應極快
     */
    private void applyBedtimeCombo(boolean enable) {
        try {
            int modeVal = enable ? 1 : 0;
            int gestureVal = enable ? 0 : 1; // 開啟就寢時，禁用喚醒手勢

            // 1. 修改全局勿擾狀態
            Settings.Global.putInt(getContentResolver(), "zen_mode", modeVal);
            
            // 2. 修改抬手喚醒 (Secure)
            Settings.Secure.putInt(getContentResolver(), "wake_gesture_enabled", gestureVal);
            
            // 3. 修改點擊喚醒 (Secure)
            Settings.Secure.putInt(getContentResolver(), "double_tap_to_wake", gestureVal);

            Log.d(TAG, "組合拳執行成功: " + (enable ? "進入就寢" : "退出就寢"));
        } catch (Exception e) {
            Log.e(TAG, "組合拳執行失敗，請檢查 WRITE_SECURE_SETTINGS 權限", e);
        }
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
