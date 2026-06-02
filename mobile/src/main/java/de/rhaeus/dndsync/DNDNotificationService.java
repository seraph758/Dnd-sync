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
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "listener connected");
        running = true;
        int currentFilter = getCurrentInterruptionFilter();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            sendDNDSync(currentFilter);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "listener disconnected");
        running = false;
        try {
            requestRebind(new ComponentName(this, 
                DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "FILTER CHANGED: " + interruptionFilter);
        
        // 核心隔離：如果是收到手錶請求而引發的內部更新，直接攔截，防止死循環
        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "內部更新攔截，防止手機回傳給手錶引發死循環");
            return;
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            sendDNDSync(interruptionFilter);
        }
    }

    private void sendDNDSync(int dndState) {
        Log.d(TAG, "手機 DataLayer 同步啟動: " + dndState);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean dndSyncSwitch = prefs.getBoolean("dnd_sync_key", true);
        boolean wearPowerSaveResponse = prefs.getBoolean("wear_power_save_response", false);
        boolean wearVibrateOnSync = prefs.getBoolean("wear_vibrate_on_sync", false);
        boolean isDndOrBedtimeActive = (dndState != 1);

        // 設定獨立的手機控手錶通道 /dnd_state/phone_to_wear
        PutDataMapRequest request = PutDataMapRequest.create(
            "/dnd_state/phone_to_wear");
        
        request.getDataMap().putBoolean("dnd_sync_switch", dndSyncSwitch);
        request.getDataMap().putBoolean("wear_power_save_response", wearPowerSaveResponse);
        request.getDataMap().putBoolean("wear_vibrate_on_sync", wearVibrateOnSync);
        request.getDataMap().putBoolean("dnd_state_active", isDndOrBedtimeActive);
        request.getDataMap().putInt("raw_dnd_value", dndState);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis()); 

        PutDataRequest putDataRequest = request.asPutDataRequest();
        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)
                .addOnSuccessListener(dataItem -> Log.d(TAG, 
                    "【手機成功寫入數據】路徑: " + dataItem.getUri().getPath()))
                .addOnFailureListener(e -> Log.e(TAG, "【手機寫入失敗】", e));
    }
}
