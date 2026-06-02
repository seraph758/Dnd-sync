package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper; 
import android.util.Log;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();
                
                Log.d(TAG, "手機收到 DataLayer 變更: " + path);

                if (path == null) continue;

                // 簽收手錶發往手機專線的反向包裹
                if (path.contains("wear_to_phone")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    int wearDndValue = dataMap.getInt("wear_dnd_value", 1);
                    
                    Log.d(TAG, "【手機簽收手錶反向包】目標值: " + wearDndValue);

                    NotificationManager mNotificationManager = (NotificationManager) 
                        getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        
                        if (wearDndValue != currentFilter) {
                            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                
                                isInternalUpdate = true;
                                mNotificationManager.setInterruptionFilter(wearDndValue);
                                Log.d(TAG, "手機勿擾狀態已成功設置為: " + wearDndValue);
                                
                                handler.postDelayed(() -> {
                                    isInternalUpdate = false;
                                    Log.d(TAG, "手機內部更新完成，恢復監聽發送");
                                }, 2000);

                            }
                        }
                    }
                }
            }
        }
    }
}
