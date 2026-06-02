package de.rhaeus.dndsync;

import android.service.notification.NotificationListenerService;
import android.util.Log;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "DNDNotificationService";

    public static final String PATH_WEAR_TO_PHONE = "/dnd_state/wear_to_phone";
    public static final String PATH_HANDSHAKE = "/dnd_state/handshake";

    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "手錶 listener connected");
        running = true;
        
        forceHandshake();
        int currentFilter = getCurrentInterruptionFilter();
        sendDNDSync(currentFilter);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "手錶 listener disconnected");
        running = false;
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "手錶 DND 改變: " + interruptionFilter);

        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "內部更新，跳過");
            return;
        }

        sendDNDSync(interruptionFilter);
    }

    private void sendDNDSync(int dndState) {
        Log.d(TAG, "手錶反向同步 DND: " + dndState);

        PutDataMapRequest request = PutDataMapRequest.create(PATH_WEAR_TO_PHONE);
        request.getDataMap().putInt("wear_dnd_value", dndState);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis());

        PutDataRequest putDataRequest = request.asPutDataRequest();
        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "✅ 手錶反向發送成功"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ 手錶反向發送失敗", e));
    }

    private void forceHandshake() {
        Log.d(TAG, "手錶發送握手信號...");
        PutDataMapRequest dataMap = PutDataMapRequest.create(PATH_HANDSHAKE);
        dataMap.getDataMap().putLong("handshake_time", System.currentTimeMillis());
        dataMap.getDataMap().putString("sender", "wear");
        
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent();

        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "✅ 手錶握手發送成功"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ 手錶握手發送失敗", e));
    }
}