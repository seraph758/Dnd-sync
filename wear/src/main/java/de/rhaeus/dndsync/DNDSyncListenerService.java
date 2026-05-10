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
            Log.d(TAG, "accessibility not connected");
            handler.post(() -> Toast.makeText(getApplicationContext(), getResources().getString(R.string.acc_not_connected), Toast.LENGTH_LONG).show());
            return;
        }

        // 開啟新線程執行耗時的模擬點擊，防止阻塞接收信號
        new Thread(() -> {
            Log.d(TAG, "accessibility connected. Perform toggle in background thread.");
            
            // 喚醒螢幕 (Wear OS 上可能需多次嘗試或配合其他方式，這裡保留 Wakelock)
            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                    "dndsync:MyWakeLock"
            );
            
            try {
                wakeLock.acquire(10*1000L); // 只需要保持 10 秒亮屏即可
                handler.post(() -> Toast.makeText(getApplicationContext(), getResources().getString(R.string.bedtime_toggle), Toast.LENGTH_SHORT).show());

                Thread.sleep(500);
                
                // 【關鍵修改】：不再用手指滑動，直接呼叫系統層打開快捷面板
                serv.openQuickSettings(); 
                Log.d(TAG, "已打開快捷面板");
                
                Thread.sleep(1500); // 面板動畫需要一點時間，給予 1.5 秒緩衝
                
                serv.clickIcon1_2(); // 點擊就寢模式圖標
                Log.d(TAG, "已點擊圖標");
                
                Thread.sleep(1000);
                
                serv.goBack();       // 返回，收起面板
                Log.d(TAG, "已返回");
                
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
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
