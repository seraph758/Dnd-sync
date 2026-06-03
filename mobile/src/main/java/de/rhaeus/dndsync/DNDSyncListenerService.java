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

    private static final String PATH_WEAR_TO_PHONE = "/dnd_state/wear_to_phone";
    private static final String PATH_HANDSHAKE = "/dnd_state/handshake";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "====== 🔥 手機端 onDataChanged 被系統呼叫！事件數量: " + dataEvents.getCount() + " ======");

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();
                
                Log.d(TAG, "📥 手機收到資料路徑: " + (path != null ? path : "null"));

                if (path == null) continue;

                // 握手處理
                if (PATH_HANDSHAKE.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String sender = dataMap.getString("sender", "");
                    if ("wear".equals(sender)) {
                        Log.d(TAG, "✅ 手機收到手錶握手 → 雙向連線確認成功！");
                    }
                    continue;
                }

                // 手錶反向同步 DND
                if (PATH_WEAR_TO_PHONE.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    int wearDndValue = dataMap.getInt("wear_dnd_value", 1);
                    
                    Log.d(TAG, "🎯 收到手錶反向 DND 請求: " + wearDndValue);

                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                        int current = nm.getCurrentInterruptionFilter();
                        if (wearDndValue != current) {
                            isInternalUpdate = true;
                            nm.setInterruptionFilter(wearDndValue);
                            Log.d(TAG, "✅ 手機 DND 已成功同步為: " + wearDndValue);
                            
                            handler.postDelayed(() -> {
                                isInternalUpdate = false;
                                Log.d(TAG, "手機內部更新標記已解除");
                            }, 2000);
                        } else {
                            Log.d(TAG, "DND 狀態相同，無需變更");
                        }
                    } else {
                        Log.w(TAG, "⚠️ 手機沒有勿擾權限或 NotificationManager 為 null");
                    }
                }
            }
        }
    }
}