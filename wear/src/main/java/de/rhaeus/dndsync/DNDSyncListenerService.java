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

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {

    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    private static final String DATA_PATH = "/dnd_state";

    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 4000;

    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler();

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                String path = event.getDataItem().getUri().getPath();
                
                // 🎯 接收並在手錶本地 SharedPrefs 中更新緩存手機發來的所有控制開關
                if (DATA_PATH.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean phonePowerSave = dataMap.getBoolean("phone_power_save_link", false);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", true);

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    prefs.edit()
                            .putBoolean("dnd_sync_switch", dndSyncSwitch)
                            .putBoolean("phone_power_save_link", phonePowerSave)
                            .putBoolean("wear_power_save_response", wearPowerSaveResponse)
                            .putBoolean("wear_vibrate_on_sync", wearVibrateOnSync)
                            .apply();

                    Log.d(TAG, "【快取成功】配置已同步：勿擾同步=" + dndSyncSwitch + 
                            ", 省電響應(開關C)=" + wearPowerSaveResponse + 
                            ", 同步震動(開關D)=" + wearVibrateOnSync);
                }
            }
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            super.onMessageReceived(messageEvent);
            return;
        }

        // 讀取開關 A 的本地快取狀態
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("dnd_sync_switch", true)) {
            Log.d(TAG, "手機已關閉勿擾同步總開關，略過本次事件");
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastExecutionTime < COOLDOWN_MS) {
            Log.d(TAG, "冷卻中...");
            return;
        }
        lastExecutionTime = currentTime;

        byte[] data = messageEvent.getData();
        if (data == null || data.length == 0) return;

        int dndState = data[0];
        applyDndState(dndState);
    }

    private void applyDndState(int dndState) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager != null) {
            int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
            if (currentFilter != dndState) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    isInternalUpdate = true;
                    mNotificationManager.setInterruptionFilter(dndState);
                    Log.d(TAG, "手錶勿擾切換成功: " + dndState);

                    // 執行自動手勢劇本與震動
                    toggleBedtimeMode();

                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                }
            }
        }
    }

    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "無障礙服務未連線，跳過手勢點擊");
            return;
        }

        // 讀取開關 D 控制是否震動
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean shouldVibrate = prefs.getBoolean("wear_vibrate_on_sync", true);
        if (shouldVibrate) {
            vibrate();
        }

        new Thread(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dnd:sync");
                wakeLock.acquire(5000L);
                
                Thread.sleep(800);

                // 1. 下拉控制中心
                serv.swipeDown();
                Log.d(TAG, "控制中心下拉手勢已發送");
                Thread.sleep(1300); // 靜置等待選單降落停穩

                // 2. 根據開關 C 判斷執行單點劇本還是連擊防吞劇本
                boolean isWearPowerSaveOpen = prefs.getBoolean("wear_power_save_response", false);

                if (isWearPowerSaveOpen) {
                    Log.d(TAG, "【開關C已開啟】執行雙連擊防吞劇本：先點擊 80% 高度，再點擊 40% 高度");
                    // 200ms 後發射點擊 (50%, 80%)
                    serv.clickIconAt80Percent(200);
                    // 550ms 後發射點擊 (50%, 40%)
                    serv.clickIcon1_2(550);
                    Thread.sleep(1000); 
                } else {
                    Log.d(TAG, "【開關C已關閉】執行常規單點劇本：僅點擊 40% 高度");
                    serv.clickIcon1_2(0);
                    Thread.sleep(500);
                }

                // 3. 返回桌面
                serv.goBack();

            } catch (Exception e) {
                Log.e(TAG, "手勢執行過程發生異常", e);
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
