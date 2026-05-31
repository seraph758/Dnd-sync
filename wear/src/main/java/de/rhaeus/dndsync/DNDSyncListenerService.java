package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService
        implements DataClient.OnDataChangedListener {

    private static final String TAG = "DNDSyncListenerService";

    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    private static final String DATA_PATH = "/dnd_state";

    // 防止短时间循环触发
    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 5000;

    // DataClient 数据最大有效时间
    private static final long DATA_EXPIRE_MS = 15000;

    public static boolean isInternalUpdate = false;

    private static final Handler handler = new Handler();

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        if (isInternalUpdate) {
            Log.d(TAG, "onDataChanged: 内部更新中，忽略此事件");
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastExecutionTime < COOLDOWN_MS) {
            Log.d(TAG, "还在冷却期，忽略本次 DataClient 信号");
            return;
        }

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED
                    && DATA_PATH.equals(event.getDataItem().getUri().getPath())) {
                
                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                long timestamp = dataMapItem.getDataMap().getLong("timestamp", 0);
                
                if (currentTime - timestamp > DATA_EXPIRE_MS) {
                    Log.d(TAG, "DataClient 数据已过期，忽略");
                    continue;
                }

                lastExecutionTime = currentTime;
                int dndState = dataMapItem.getDataMap().getInt("dnd_state");
                Log.d(TAG, "收到 DataClient DND: " + dndState);
                
                applyDndState(dndState);
            }
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onMessageReceived: " + messageEvent);
        }

        // 拦截 /settings-sync 路径，同步并持久化缓存省电开关状态
        if ("/settings-sync".equalsIgnoreCase(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            if (data != null && data.length >= 3) {
                boolean powerSave = data[1] == 1;
                boolean wearPowerSave = data[2] == 1;
                boolean isPowerSaveEnabledOnPhone = powerSave && wearPowerSave;

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putBoolean("phone_wear_power_save_state", isPowerSaveEnabledOnPhone).apply();
                Log.d(TAG, "已同步并缓存手机端省电开关状态: " + isPowerSaveEnabledOnPhone);
            }
            return; 
        }

        if (!messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            super.onMessageReceived(messageEvent);
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastExecutionTime < COOLDOWN_MS) {
            Log.d(TAG, "還在冷卻期，忽略本次信號");
            return;
        }
        lastExecutionTime = currentTime;

        byte[] data = messageEvent.getData();
        if (data == null || data.length == 0) {
            Log.d(TAG, "MessageClient 数据为空");
            return;
        }

        int dndState = data[0];
        Log.d(TAG, "收到 MessageClient DND: " + dndState);
        applyDndState(dndState);
    }

    private void applyDndState(int dndState) {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (mNotificationManager != null) {
            int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
            if (currentFilter != dndState) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    isInternalUpdate = true;
                    mNotificationManager.setInterruptionFilter(dndState);
                    Log.d(TAG, "成功设置手錶勿扰状态为: " + dndState);
                    
                    toggleBedtimeMode();
                    
                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                } else {
                    Log.d(TAG, "未获取勿扰权限，无法同步");
                }
            }
        }
    }

    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();

        if (serv == null) {
            Log.d(TAG, "AccessibilityService 未连接");
            return;
        }

        new Thread(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "dnd:sync"
                );

                wakeLock.acquire(5000L);
                Thread.sleep(1000);

                serv.swipeDown();
                Log.d(TAG, "执行手势下拉");
                Thread.sleep(1200);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean isPhonePowerSaveOpen = prefs.getBoolean("phone_wear_power_save_state", false);

                if (isPhonePowerSaveOpen) {
                    Log.d(TAG, "手机端省电开关联动：准备先点击 (50%, 80%)，稍后点击 (50%, 40%)");
                    
                    // 1. 先点击 80% 高度地方 (0ms 立即发射)
                    serv.clickIconAt80Percent(0);
                    
                    // 🎯 优化：将 150ms 改为 350ms，给手錶 UI 留出足够的按钮反彈與加載緩衝時間，確保不丟包
                    serv.clickIcon1_2(350);
                    
                    Thread.sleep(700); 
                } else {
                    Log.d(TAG, "手机端省电开关未联动：仅点击原设定 (50%, 40%) 的位置");
                    serv.clickIcon1_2(0);
                    Thread.sleep(400);
                }

                serv.goBack();

            } catch (Exception e) {
                Log.e(TAG, "toggleBedtimeMode 异常", e);
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
