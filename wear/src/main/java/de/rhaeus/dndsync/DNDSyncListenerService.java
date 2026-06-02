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

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo("/dnd_state") == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    // 1. 讀取手機端同步過來的各項控制開關與狀態
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    
                    // 🎯 完美對齊：讀取您手機端的 "wear_vibrate_on_sync" 開關狀態
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", false);
                    
                    // 讀取當前手機的勿擾或睡眠狀態（請與您手機端塞入 DataMap 的 Key 保持一致，此處以 "dnd_state_active" 為例）
                    boolean isDndOrBedtimeActive = dataMap.getBoolean("dnd_state_active", false);

                    Log.d(TAG, "收到同步數據: 託管開關=" + wearPowerSaveResponse + ", 震動開關=" + wearVibrateOnSync + ", 當前狀態=" + isDndOrBedtimeActive);

                    // 2. 🎯 核心修復：只有當手機端傳來的震動開關為 true 時，手錶才執行震動方法
                    if (wearVibrateOnSync) {
                        Log.d(TAG, "手機允許震動，手錶執行短震動反饋");
                        triggerWatchVibration();
                    } else {
                        Log.d(TAG, "手機關閉了震動開關，安靜同步，不觸發震動");
                    }

                    // 3. 省電模式自動點擊託管邏輯（直接連續點擊 80% 和 40% 的閉環控制）
                    if (wearPowerSaveResponse) {
                        DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            if (isDndOrBedtimeActive) {
                                Log.d(TAG, "觸發進入睡眠：直接點擊80%，緊接著點擊40%");
                                accessService.clickAtPosition(0.8f, 0.5f);
                                accessService.clickAtPosition(0.4f, 0.5f);
                            } else {
                                Log.d(TAG, "觸發退出睡眠：同樣直接點擊80%，緊接著點擊40%");
                                accessService.clickAtPosition(0.8f, 0.5f);
                                accessService.clickAtPosition(0.4f, 0.5f);
                            }
                        } else {
                            Log.w(TAG, "模擬點擊失敗：手錶無障礙權限未開啟");
                        }
                    }
                }
            }
        }
    }

    /**
     * 封裝的手錶短震動方法（嚴格採用 Java 語法，拒絕點屬性簡寫）
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
