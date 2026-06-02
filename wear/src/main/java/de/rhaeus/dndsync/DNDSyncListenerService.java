package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
    private static final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();
                
                Log.d(TAG, "手錶收到變更，路徑: " + path);

                // 簽收手機發往手錶專線的包裹
                if (path != null && path.contains("phone_to_wear")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    int rawDndValue = dataMap.getInt("raw_dnd_value", 1);
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", false);

                    Log.d(TAG, "【手錶簽收成功】目標值=" + rawDndValue);

                    if (!dndSyncSwitch) {
                        continue;
                    }

                    NotificationManager mNotificationManager = (NotificationManager) 
                        getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (rawDndValue != currentFilter) {
                            // 鎖定手錶本地發射源，防止狀態改變觸發回傳死循環
                            isInternalUpdate = true;
                            mNotificationManager.setInterruptionFilter(rawDndValue);
                            
                            handler.postDelayed(() -> {
                                isInternalUpdate = false;
                                Log.d(TAG, "手錶內部更新完成，解除鎖定");
                            }, 2000);
                        }
                    }

                    // 1. 震動控制
                    if (wearVibrateOnSync) {
                        triggerWatchVibration();
                    }

                    // 2. 省電模式自動點擊連動託管
                    if (wearPowerSaveResponse) {
                        DNDSyncAccessService accessService = 
                            DNDSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            accessService.clickIconAt80Percent(0);   
                            accessService.clickIcon1_2(200);         
                        }
                    }
                }
            }
        }
    }

    private void triggerWatchVibration() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, 
                        VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "手錶震動出錯", e);
        }
    }
}
