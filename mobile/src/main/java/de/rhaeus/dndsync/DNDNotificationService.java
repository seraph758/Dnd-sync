package de.rhaeus.dndsync;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "DNDNotificationService";
    public static boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SERVICE CREATED");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "listener connected");
        running = true;

        int currentFilter = getCurrentInterruptionFilter();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean("dnd_sync_key", true)) {
            new Thread(() -> sendDNDSync(currentFilter)).start();
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "listener disconnected");
        running = false;
        try {
            requestRebind(new ComponentName(this, DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "FILTER CHANGED: " + interruptionFilter);

        // 🎯 核心隔離：如果是收到手錶請求而引發的內部更新，直接攔截，不允許回傳給手錶
        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "內部更新攔截，防止手機回傳給手錶引發死循環");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            new Thread(() -> sendDNDSync(interruptionFilter)).start();
        }
    }

    // =====================================================
    // 🎯 核心重構：利用 DataClient 將勿擾狀態與所有開關打包發送
    // =====================================================
    private void sendDNDSync(int dndState) {
        Log.d(TAG, "手機開始打包 DataLayer 數據發往手錶: " + dndState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean dndSyncSwitch = prefs.getBoolean("dnd_sync_key", true);
        boolean wearPowerSaveResponse = prefs.getBoolean("wear_power_save_response", false);
        boolean wearVibrateOnSync = prefs.getBoolean("wear_vibrate_on_sync", false);
        
        // 1 代表 INTERRUPTION_FILTER_ALL (勿擾關閉)
        boolean isDndOrBedtimeActive = (dndState != 1);

        // 🎯 設定獨立的手機控手錶專線 Path
        PutDataMapRequest request = PutDataMapRequest.create("/dnd_state/phone_to_wear");
        
        request.getDataMap().putBoolean("dnd_sync_switch", dndSyncSwitch);
        request.getDataMap().putBoolean("wear_power_save_response", wearPowerSaveResponse);
        request.getDataMap().putBoolean("wear_vibrate_on_sync", wearVibrateOnSync);
        request.getDataMap().putBoolean("dnd_state_active", isDndOrBedtimeActive);
        request.getDataMap().putInt("raw_dnd_value", dndState);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis()); // 確保每次都會觸發手錶變更

        PutDataRequest putDataRequest = request.asPutDataRequest();
        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "【手機控手錶】DataLayer 數據寫入成功: " + dataItem.getUri()))
                .addOnFailureListener(e -> Log.e(TAG, "【手機控手錶】DataLayer 寫入失敗", e));
    }
}
