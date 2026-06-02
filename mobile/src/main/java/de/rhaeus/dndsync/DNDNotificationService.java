package de.rhaeus.dndsync;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "DNDNotificationService";

    // 統一路徑常數
    public static final String PATH_PHONE_TO_WEAR = "/dnd_state/phone_to_wear";
    public static final String PATH_HANDSHAKE = "/dnd_state/handshake";

    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "手機 listener connected");
        running = true;
        
        forceHandshake();
        int currentFilter = getCurrentInterruptionFilter();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_switch", true)) {
            sendDNDSync(currentFilter);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "手機 listener disconnected");
        running = false;
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "手機 DND 狀態改變: " + interruptionFilter);
        
        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "內部更新，跳過防止循環");
            return;
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_switch", true)) {
            sendDNDSync(interruptionFilter);
        }
    }

    private void sendDNDSync(int dndState) {
        Log.d(TAG, "手機發送 DND 同步: " + dndState);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        boolean dndSyncSwitch = prefs.getBoolean("dnd_sync_switch", true);
        boolean wearPowerSaveResponse = prefs.getBoolean("wear_power_save_response", false);
        boolean wearVibrateOnSync = prefs.getBoolean("wear_vibrate_on_sync", true);

        PutDataMapRequest request = PutDataMapRequest.create(PATH_PHONE_TO_WEAR);
        
        request.getDataMap().putBoolean("dnd_sync_switch", dndSyncSwitch);
        request.getDataMap().putBoolean("wear_power_save_response", wearPowerSaveResponse);
        request.getDataMap().putBoolean("wear_vibrate_on_sync", wearVibrateOnSync);
        request.getDataMap().putBoolean("dnd_state_active", dndState != 1);
        request.getDataMap().putInt("raw_dnd_value", dndState);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis());

        PutDataRequest putDataRequest = request.asPutDataRequest();
        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "✅ 手機寫入成功: " + dataItem.getUri().getPath()))
                .addOnFailureListener(e -> Log.e(TAG, "❌ 手機寫入失敗", e));
    }

    private void forceHandshake() {
        Log.d(TAG, "手機發送握手信號...");
        PutDataMapRequest dataMap = PutDataMapRequest.create(PATH_HANDSHAKE);
        dataMap.getDataMap().putLong("handshake_time", System.currentTimeMillis());
        dataMap.getDataMap().putString("sender", "phone");
        
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent();

        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "✅ 手機握手發送成功"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ 手機握手發送失敗", e));
    }
}