package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    // 新增：防抖冷却逻辑变量
    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 10000; // 500毫秒冷却，防止死循环
    // 【新增】：用於標記是否為內部觸發的更新，讓 NotificationService 讀取
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(android.os.Looper.getMainLooper());
    @Override
    public void onMessageReceived (@NonNull MessageEvent messageEvent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onMessageReceived: " + messageEvent);
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH);

            // 1. 基礎冷卻檢查 (保留原有邏輯)
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecutionTime < COOLDOWN_MS) {
                Log.d(TAG, "檢測到極短時間內的重複觸發，忽略此指令");
                return;
            }
            lastExecutionTime = currentTime;

            // 2. 解析數據
            byte[] data = messageEvent.getData();
            byte dndStatePhone = data[0];
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int filterState = mNotificationManager.getCurrentInterruptionFilter();
            byte currentDndState = (byte) filterState;

            // 3. 核心邏輯：狀態不一致時執行同步
            if (dndStatePhone != currentDndState) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    
                    // 【關鍵修正】：立起標誌位，防止回傳手機
                    isInternalUpdate = true;
                    Log.d(TAG, "收到手機同步請求，鎖定手錶回傳邏輯");

                    // 執行震動
                    if (prefs.getBoolean("vibrate_key", false)) { vibrate(); }

                    // 執行就寢模式切換 (模擬點擊)
                    if (prefs.getBoolean("bedtime_key", true)) { toggleBedtimeMode(); }

                    // 執行勿擾設置
                    mNotificationManager.setInterruptionFilter((int)dndStatePhone);
                    Log.d(TAG, "手錶 DND 成功設置為 " + dndStatePhone);

                    // 2秒後解除鎖定
                    handler.postDelayed(() -> {
                        isInternalUpdate = false;
                        Log.d(TAG, "手錶內部更新完成，恢復監聽發送");
                    }, 2000);

                } else {
                    Log.d(TAG, "嘗試設置 DND 但缺少權限");
                }
            }
        } else {
            super.onMessageReceived(messageEvent);
        }
    }


    // --- 以下完整保留原版所有功能函数 ---

    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "accessibility not connected");
            Handler mHandler = new Handler(getMainLooper());
            mHandler.post(() -> Toast.makeText(getApplicationContext(), getResources().getString(R.string.acc_not_connected), Toast.LENGTH_LONG).show());
            return;
        }

        Log.d(TAG, "accessibility connected. Perform toggle.");
        // 唤醒屏幕
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:MyWakeLock");
        wakeLock.acquire(2*60*1000L);

        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(() -> Toast.makeText(getApplicationContext(), getResources().getString(R.string.bedtime_toggle), Toast.LENGTH_SHORT).show());

        try {
            Thread.sleep(1000);
            serv.swipeDown();    // 下拉面板
            Thread.sleep(1000);
            serv.clickIcon1_2(); // 点击就寝模式图标
            Thread.sleep(1000);
            serv.goBack();       // 返回
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
