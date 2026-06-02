package de.rhaeus.dndsync;

import android.content.Context;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListener";

    // 🎯 完美修復 1：補回 DNDNotificationService 引用需要的變量，防止編譯報錯
    public static boolean isInternalUpdate = false;

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo("/dnd_state") == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    // 1. 讀取手機端同步過來的設定與狀態
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", false);
                    boolean isDndOrBedtimeActive = dataMap.getBoolean("dnd_state_active", false);

                    Log.d(TAG, "收到同步數據: 託管開關=" + wearPowerSaveResponse + ", 震動開關=" + wearVibrateOnSync + ", 當前狀態=" + isDndOrBedtimeActive);

                    // 2. 震動控制邏輯：完全聽從手機端 "wear_vibrate_on_sync" 開關的指揮
                    if (wearVibrateOnSync) {
                        Log.d(TAG, "手機允許震動，手錶執行短震動反饋");
                        triggerWatchVibration();
                    } else {
                        Log.d(TAG, "手機關閉了震動開關，安靜同步，不觸發震動");
                    }

                    // 3. 省電模式自動點擊託管邏輯
                    if (wearPowerSaveResponse) {
                        isInternalUpdate = true;
                        
                        DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            if (isDndOrBedtimeActive) {
                                Log.d(TAG, "觸發進入睡眠：調用無障礙隊列，先點擊80%，200ms後點擊40%");
                                
                                // 🎯 完美對齊：呼叫您 AccessService 裡的自帶延遲手勢點擊方法
                                accessService.clickIconAt80Percent(0);   // 0ms 立即點擊 80%
                                accessService.clickIcon1_2(200);         // 200ms 後排隊點擊 40%
                                
                            } else {
                                Log.d(TAG, "觸發退出睡眠：同樣調用無障礙隊列，先點擊80%，200ms後點擊40%");
                                
                                // 根據您的反饋，關閉和開啟操作完全一樣
                                accessService.clickIconAt80Percent(0);   // 0ms 立即點擊 80%
                                accessService.clickIcon1_2(200);         // 200ms 後排隊點擊 40%
                            }
                        } else {
                            Log.w(TAG, "模擬點擊失敗：手錶無障礙權限未開啟");
                        }
                        
                        isInternalUpdate = false;
                    }
                }
            }
        }
    }

    /**
     * 手錶短震動方法（標準 Java 語法）
     */
    private void triggerWatchVibration() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "手錶執行震動時出錯: " + e.getMessage());
        }
    }
}
