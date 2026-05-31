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
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService
        implements DataClient.OnDataChangedListener {

    private static final String TAG = "DNDSyncListenerService";

    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    private static final String DATA_PATH = "/dnd_state";
    
    // 🎯 這是手機端 DataClient 發送設置的路徑
    private static final String SETTINGS_DATA_PATH = "/settings-sync";

    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 5000;
    private static final long DATA_EXPIRE_MS = 15000;

    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler();

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        long currentTime = System.currentTimeMillis();

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                String path = event.getDataItem().getUri().getPath();
                
                // 🎯 【核心修正】：在這裡精確攔截手機發來的 DataClient 設置數據
                if (SETTINGS_DATA_PATH.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    boolean powerSave = dataMap.getBoolean("powerSave", false);
                    boolean wearPowerSave = dataMap.getBoolean("wearPowerSave", false);
                    
                    // 當手機聯動與手錶省電同時為 true 時，判定為開啟
                    boolean isPowerSaveEnabled = powerSave && wearPowerSave;

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    prefs.edit().putBoolean("phone_wear_power_save_state", isPowerSaveEnabled).apply();
                    Log.d(TAG, "【成功】收到手機 DataClient 同步，緩存省電開關狀態為: " + isPowerSaveEnabled);
                    continue; // 處理完設置，跳過後續 DND 處理
                }

                // 原有的 /dnd_state 處理邏輯
                if (DATA_PATH.equals(path)) {
                    if (isInternalUpdate) {
                        Log.d(TAG, "onDataChanged: 內部更新中，忽略此事件");
                        continue;
                    }
                    if (currentTime - lastExecutionTime < COOLDOWN_MS) {
                        Log.d(TAG, "還在冷卻期，忽略本次 DataClient 訊號");
                        continue;
                    }

                    DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                    long timestamp = dataMapItem.getDataMap().getLong("timestamp", 0);
                    
                    if (currentTime - timestamp > DATA_EXPIRE_MS) {
                        Log.d(TAG, "DataClient 數據已過期，忽略");
                        continue;
                    }

                    lastExecutionTime = currentTime;
                    int dndState = dataMapItem.getDataMap().getInt("dnd_state");
                    Log.d(TAG, "收到 DataClient DND: " + dndState);
                    applyDndState(dndState);
                }
            }
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onMessageReceived: " + messageEvent);
        }

        // 🎯 這裡只留下純粹的 MessageClient 勿擾路徑監聽，去掉了錯誤的 /settings-sync 攔截
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
            Log.d(TAG, "MessageClient 數據為空");
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
                    Log.d(TAG, "成功設置手錶勿擾狀態為: " + dndState);
                    
                    // 觸發自動點擊劇本
                    toggleBedtimeMode();
                    
                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                } else {
                    Log.d(TAG, "未獲取勿擾權限，無法同步");
                }
            }
        }
    }

    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();

        if (serv == null) {
            Log.d(TAG, "AccessibilityService 未連接");
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

                // 1. 下拉控制中心
                serv.swipeDown();
                Log.d(TAG, "執行手勢下拉");
                
                // 🎯 稍微拉長一點點等待時間，確保通知欄在手錶上徹底停穩
                Thread.sleep(1300);

                // 2. 讀取已經被 DataClient 正確更新的開關狀態
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean isPhonePowerSaveOpen = prefs.getBoolean("phone_wear_power_save_state", false);

                if (isPhonePowerSaveOpen) {
                    Log.d(TAG, "【劇本 A】手機端省電開關已打開：先點 (50%, 80%)，再點 (50%, 40%)");
                    
                    // 先點擊 80% 高度地方 (給 250ms 延遲確保按鈕可點擊)
                    serv.clickIconAt80Percent(250);
                    
                    // 留出 400ms 間隔，在 650ms 時精準點擊原有的 40% 高度
                    serv.clickIcon1_2(650);
                    
                    Thread.sleep(1000); 
                } else {
                    Log.d(TAG, "【劇本 B】手機端省電開關未打開：僅點擊原設定 (50%, 40%) 的位置");
                    serv.clickIcon1_2(200);
                    Thread.sleep(600);
                }

                // 3. 返回桌面
                serv.goBack();

            } catch (Exception e) {
                Log.e(TAG, "toggleBedtimeMode 異常", e);
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
