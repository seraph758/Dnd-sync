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
    private static final long COOLDOWN_MS = 1000; // 500毫秒冷却，防止死循环

    @Override
    public void onMessageReceived (@NonNull MessageEvent messageEvent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onMessageReceived: " + messageEvent);
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH);

            // 1. 冷却逻辑检查
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecutionTime < COOLDOWN_MS) {
                Log.d(TAG, "检测到极短时间内的重复触发，忽略此指令（防止同步死循环）");
                return;
            }
            lastExecutionTime = currentTime;

            // 2. 原版逻辑：震动
            boolean vibrate = prefs.getBoolean("vibrate_key", false);
            if (vibrate) {
                vibrate();
            }

            // 3. 原版逻辑：解析 DND 状态
            byte[] data = messageEvent.getData();
            byte dndStatePhone = data[0];
            Log.d(TAG, "dndStatePhone: " + dndStatePhone);

            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int filterState = mNotificationManager.getCurrentInterruptionFilter();
            byte currentDndState = (byte) filterState;
            Log.d(TAG, "currentDndState: " + currentDndState);

            // 4. 核心逻辑判断
            if (dndStatePhone != currentDndState) {
                Log.d(TAG, "状态不一致，开始同步流程");
                
                // 执行就寝模式切换（原版模拟点击逻辑）
                boolean useBedtimeMode = prefs.getBoolean("bedtime_key", true);
                if (useBedtimeMode) {
                    toggleBedtimeMode();
                }

                // 执行勿扰模式设置（原版 API 逻辑）
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    mNotificationManager.setInterruptionFilter((int)dndStatePhone);
                    Log.d(TAG, "DND 成功设置为 " + dndStatePhone);
                } else {
                    Log.d(TAG, "尝试设置 DND 但缺少权限 (allow_dnd)");
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
