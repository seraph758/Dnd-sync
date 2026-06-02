package de.rhaeus.dndsync;

import android.service.notification.NotificationListenerService;
import android.util.Log;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "DNDNotificationService";
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "listener connected");
        running = true;
        int currentFilter = getCurrentInterruptionFilter();
        sendDNDSync(currentFilter);
    }

    @Override
    public void onListenerDisconnected() {
        Log.d(TAG, "listener disconnected");
        running = false;
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "手錶本機勿擾狀態改變: " + interruptionFilter);

        // 🎯 核心隔離：如果是因為收到手機請求或無障礙模擬點擊導致的變更，直接攔截，禁止回傳給手機
        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "手錶內部更新攔截，防止反向回傳手機引發死循環");
            return;
        }

        sendDNDSync(interruptionFilter);
    }

    // =====================================================
    // 🎯 核心重構：手錶利用 DataClient 將狀態反向發往手機專線
    // =====================================================
    private void sendDNDSync(int dndState) {
        Log.d(TAG, "手錶準備將勿擾狀態寫入 DataLayer 同步給手機: " + dndState);

        // 🎯 設定獨立的手錶控手機專線 Path
        PutDataMapRequest request = PutDataMapRequest.create("/dnd_state/wear_to_phone");
        request.getDataMap().putInt("wear_dnd_value", dndState);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis());

        PutDataRequest putDataRequest = request.asPutDataRequest();
        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "【手錶控手機】狀態成功寫入 DataLayer: " + dataItem.getUri()))
                .addOnFailureListener(e -> Log.e(TAG, "【手錶控手機】狀態寫入 DataLayer 失敗", e));
    }
}
