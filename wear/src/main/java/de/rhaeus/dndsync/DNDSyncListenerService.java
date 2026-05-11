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

import com.google.android.gms.wearable.Wearable;
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
    @Override
    public void onCreate() {
    super.onCreate();

    Wearable.getCapabilityClient(this)
            .addLocalCapability("dnd_sync")
            .addOnSuccessListener(unused ->
                    Log.d(TAG, "动态注册 capability 成功"))
            .addOnFailureListener(e ->
                    Log.e(TAG, "动态注册 capability 失败", e));
}
    @Override
    public void onDestroy() {
    super.onDestroy();

    Wearable.getCapabilityClient(this)
            .removeLocalCapability("dnd_sync");

    Log.d(TAG, "capability 已移除");
}
    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) return;

        new Thread(() -> {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dnd:sync");
                
                // 1. 唤醒
                wakeLock.acquire(5000L);
                
                // 2. 关键：等待屏幕完全点亮，否则手势会失效
                Thread.sleep(1000); 

                // 3. 调用被证明有效的 swipeDown
                serv.swipeDown(); 
                Log.d(TAG, "执行手势下拉");

                // 4. 等待面板拉下来的动画完成
                Thread.sleep(1200);

                // 5. 点击图标
                serv.clickIcon1_2();
                
                // 6. 结束返回
                Thread.sleep(800);
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
