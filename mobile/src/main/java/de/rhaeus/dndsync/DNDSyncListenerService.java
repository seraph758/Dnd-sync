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
                
                // 🎯 簽收手錶發往手機專線的反向包
                if (path != null && path.equals("/dnd_state/wear_to_phone")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    int wearDndValue = dataMap.getInt("wear_dnd_value", 1);
                    
                    Log.d(TAG, "【手機端簽收反向包】收到手錶勿擾同步請求，目標值: " + wearDndValue);

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        
                        if (wearDndValue != currentFilter) {
                            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                
                                // 🎯 鎖定手機本地發射源，防止手機修改後又再次回傳給手錶
                                isInternalUpdate = true;
                                Log.d(TAG, "鎖定手機本地發送邏輯，開始執行反向修改");
                                
                                mNotificationManager.setInterruptionFilter(wearDndValue);
                                Log.d(TAG, "手機勿擾狀態已成功設置為: " + wearDndValue);
                                
                                // 2秒後解除鎖定
                                handler.postDelayed(() -> {
                                    isInternalUpdate = false;
                                    Log.d(TAG, "手機內部更新完成，恢復監聽發送");
                                }, 2000);

                            } else {
                                Log.d(TAG, "嘗試設定勿擾，但手機未被授予通知策略存取權限");
                            }
                        }
                    }
                }
            }
        }
    }
}
