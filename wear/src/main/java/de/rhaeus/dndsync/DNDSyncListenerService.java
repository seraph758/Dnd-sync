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
    public static boolean isInternalUpdate = false;

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();
                
                // 🎯 核心修復 1：改用 contains 模糊匹配，通殺 /dnd_status 和 /dnd_state
                if (path != null && path.contains("dnd")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    
                    // 🎯 核心修復 2：精準對齊您手機端的 Key "wear_vibrate_on_sync"
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", false);
                    boolean isDndOrBedtimeActive = dataMap.getBoolean("dnd_state_active", false);

                    Log.d(TAG, "手錶成功攔截協議! 路徑: " + path + " | 託管=" + wearPowerSaveResponse + " | 震動=" + wearVibrateOnSync + " | 狀態=" + isDndOrBedtimeActive);

                    // 🎯 核心修復 3：只有當手機端傳來的震動開關為 true 時，手錶才允許震動
                    if (wearVibrateOnSync) {
                        Log.d(TAG, "震動開關開啟，手錶執行短震動反饋");
                        triggerWatchVibration();
                    } else {
                        Log.d(TAG, "震動開關關閉，手錶安靜同步，絕不震動");
                    }

                    // 4. 省電模式自動點擊託管邏輯（調用您 AccessService 裡現有的 clickIcon 方法）
                    if (wearPowerSaveResponse) {
                        isInternalUpdate = true;
                        DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            Log.d(TAG, "執行無障礙點擊：80% 緊接 40%");
                            accessService.clickIconAt80Percent(0);   // 0ms 立即點擊 80%
                            accessService.clickIcon1_2(200);         // 200ms 後排隊點擊 40%
                        } else {
                            Log.w(TAG, "模擬點擊失敗：手錶無障礙服務未啟動");
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
