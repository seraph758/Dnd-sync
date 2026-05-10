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
    if (serv == null) {
        Log.e(TAG, "辅助功能未连接");
        return;
    }

    // 建议放在新线程运行，避免阻塞消息接收
    new Thread(() -> {
        try {
            // 1. 获取唤醒锁并点亮屏幕
            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                "dndsync:MyWakeLock"
            );
            wakeLock.acquire(5000L); 

            // 2. 等待屏幕完全亮起
            Thread.sleep(800);

            // 3. 【核心修改】调用你 AccessService 里定义的原生下拉方法
            serv.openQuickSettings(); 
            Log.d(TAG, "已触发原生下拉 (GLOBAL_ACTION_QUICK_SETTINGS)");

            // 4. 下拉面板弹出需要动画时间，等待 1.2 秒
            Thread.sleep(1200);

            // 5. 执行点击（请确保 icon 坐标在屏幕 40% 高度处是准的）
            serv.clickIcon1_2();
            Log.d(TAG, "已尝试点击图标");

            // 6. 等待点击生效后收起面板
            Thread.sleep(800);
            serv.goBack();

            if (wakeLock.isHeld()) wakeLock.release();
        } catch (InterruptedException e) {
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
