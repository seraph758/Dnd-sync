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
        // 1. 基础冷却检查 (防止死循环)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastExecutionTime < COOLDOWN_MS) {
            return;
        }
        lastExecutionTime = currentTime;

        // 2. 解析数据
        byte[] data = messageEvent.getData();
        byte dndStatePhone = data[0];
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        int filterState = mNotificationManager.getCurrentInterruptionFilter();
        byte currentDndState = (byte) filterState;

        // 3. 核心逻辑：状态不一致时执行同步
        if (dndStatePhone != currentDndState) {
            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                
                // 【修改】：锁定回传标记位，延长至 5 秒以覆盖模拟点击全过程
                isInternalUpdate = true;
                handler.postDelayed(() -> {
                    isInternalUpdate = false;
                    Log.d(TAG, "锁定解除，恢复状态监听");
                }, 5000); 

                // 执行震动
                if (prefs.getBoolean("vibrate_key", false)) { vibrate(); }

                // 【关键逻辑调整】：判断是否需要模拟点击
                if (prefs.getBoolean("bedtime_key", true)) {
                    Log.d(TAG, "检测到开启就寝模式同步，仅执行模拟点击动作");
                    toggleBedtimeMode(); 
                    // 执行完模拟点击后直接返回，不再执行下方的 setInterruptionFilter
                    return; 
                }

                // 如果未开启就寝模式同步，则执行常规的勿扰模式设置
                mNotificationManager.setInterruptionFilter((int)dndStatePhone);
                Log.d(TAG, "执行常规勿扰模式设置: " + dndStatePhone);

            } else {
                Log.d(TAG, "缺少勿扰模式访问权限");
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
