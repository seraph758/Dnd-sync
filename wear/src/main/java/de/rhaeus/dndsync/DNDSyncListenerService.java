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
                
                // 🎯 簽收手機發往手錶專線的包裹
                if (path != null && path.equals("/dnd_state/phone_to_wear")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    int rawDndValue = dataMap.getInt("raw_dnd_value", 1);
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", false);

                    Log.d(TAG, "【手錶端簽收成功】目標勿擾值=" + rawDndValue + " | 託管=" + wearPowerSaveResponse + " | 震動=" + wearVibrateOnSync);

                    if (!dndSyncSwitch) {
                        Log.d(TAG, "手機同步總開關已關閉，跳過本次同步");
                        continue;
                    }

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (rawDndValue != currentFilter) {
                            // 🎯 鎖定手錶本地發射源，防止手錶改變勿擾後又回傳給手機
                            isInternalUpdate = true;
                            mNotificationManager.setInterruptionFilter(rawDndValue);
                            Log.d(TAG, "手錶本機勿擾狀態已成功設置為: " + rawDndValue);
                            
                            handler.postDelayed(() -> {
                                isInternalUpdate = false;
                                Log.d(TAG, "手錶內部更新完成，解除發射鎖定");
                            }, 2000);
                        }
                    }

                    // 1. 震動反饋控制：完全聽從手機端 wearVibrateOnSync 開關
                    if (wearVibrateOnSync) {
                        Log.d(TAG, "震動開關開啟，手錶執行短震動反饋");
                        triggerWatchVibration();
                    } else {
                        Log.d(TAG, "震動開關關閉，手錶保持安靜同步");
                    }

                    // 2. 省電模式自動點擊連動託管
                    if (wearPowerSaveResponse) {
                        DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            Log.d(TAG, "執行無障礙點擊：80% 緊接 40%");
                            accessService.clickIconAt80Percent(0);   // 0ms
                            accessService.clickIcon1_2(200);         // 200ms
                        } else {
                            Log.w(TAG, "自動點擊失敗：手錶端的無障礙自動點擊服務未開啟！");
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
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "手錶震動出錯: " + e.getMessage());
        }
    }
}
