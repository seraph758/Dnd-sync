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

    // 修改：縮短冷卻時間到 5000ms，提高反應靈敏度
    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 5000; 
    
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(android.os.Looper.getMainLooper());

    @Override
    public void onMessageReceived (@NonNull MessageEvent messageEvent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onMessageReceived: " + messageEvent);
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecutionTime < COOLDOWN_MS) {
                Log.d(TAG, "還在冷卻期，忽略本次信號");
                return;
            }
            lastExecutionTime = currentTime;

            byte[] data = messageEvent.getData();
            byte dndStatePhone = data[0];
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int filterState = mNotificationManager.getCurrentInterruptionFilter();
            byte currentDndState = (byte) filterState;

            if (dndStatePhone != currentDndState) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    
                    // 鎖定狀態 5 秒，防止模擬點擊觸發回傳死循環
                    isInternalUpdate = true;
                    handler.postDelayed(() -> {
                        isInternalUpdate = false;
                        Log.d(TAG, "鎖定解除，恢復狀態監聽");
                    }, 5000); 

                    if (prefs.getBoolean("vibrate_key", false)) { vibrate(); }

                    if (prefs.getBoolean("bedtime_key", true)) {
                        Log.d(TAG, "執行睡眠模式模擬點擊");
                        toggleBedtimeMode(); 
                        return; // 點擊後直接返回，不再調用系統 API
                    }

                    mNotificationManager.setInterruptionFilter((int)dndStatePhone);
                    Log.d(TAG, "常規 API 勿擾模式設置完成");

                } else {
                    Log.d(TAG, "缺少勿擾模式訪問權限");
                }
            }
        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    private void toggleBedtimeMode() {
    DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
    if (serv == null) return;

    new Thread(() -> {
        try {
            // 1. 点亮屏幕
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dnd:lock");
            wakeLock.acquire(5000L);

            // 2. 关键步骤：先回到主页
            // 这样可以确保下拉动作在系统表盘层级触发，成功率接近 100%
            serv.goHome();
            Thread.sleep(1000); 

            // 3. 执行下拉
            serv.openQuickSettings(); 
            Thread.sleep(1200); // 等待下拉动画完成

            // 4. 执行点击
            serv.clickIcon1_2();
            Thread.sleep(800);

            // 5. 收起并返回
            serv.goBack();

            if (wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }).start();
}

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
