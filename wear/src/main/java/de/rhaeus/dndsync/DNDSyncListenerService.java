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
                
                Log.d(TAG, "手錶監聽到 DataLayer 信號更新，路徑: " + path);

                if (path == null) continue;

                // 🎯 核心修復：響應手機端的連線驗證信號
                if (path.contains("handshake")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String sender = dataMap.getString("sender", "");
                    if ("phone".equals(sender)) {
                        Log.d(TAG, "【連線成功】手錶成功簽收來自手機端的連線驗證信號！雙向通訊正常。");
                    }
                    continue;
                }

                // 簽收手機發往手錶專線的包裹
                if (path.contains("phone_to_wear")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    int rawDndValue = dataMap.getInt("raw_dnd_value", 1);
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", false);

                    Log.d(TAG, "【數據簽收成功】勿擾目標值=" + rawDndValue + " | 託管=" + wearPowerSaveResponse);

                    if (!dndSyncSwitch) {
                        Log.d(TAG, "手機同步總開關已關閉，跳過本次同步");
                        continue;
                    }

                    NotificationManager mNotificationManager = (NotificationManager) 
                        getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (rawDndValue != currentFilter) {
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
