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
        
        // 🎯 核心修復：連線成功時，立刻進行一次強制 DataLayer 握手驗證連線
        forceHandshake();
        
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
            requestRebind(new ComponentName(this, DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "FILTER CHANGED: " + interruptionFilter);
        
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

        PutDataMapRequest request = PutDataMapRequest.create("/dnd_state/phone_to_wear");
        
        request.getDataMap().putBoolean("dnd_sync_switch", dndSyncSwitch);
        request.getDataMap().putBoolean("wear_power_save_response", wearPowerSaveResponse);
        request.getDataMap().putBoolean("wear_vibrate_on_sync", wearVibrateOnSync);
        request.getDataMap().putBoolean("dnd_state_active", isDndOrBedtimeActive);
        request.getDataMap().putInt("raw_dnd_value", dndState);
        // 🎯 強制加入毫秒時間戳，確保 DataClient 每次內容都不一樣，百分之百觸發傳輸
        request.getDataMap().putLong("timestamp", System.currentTimeMillis()); 

        PutDataRequest putDataRequest = request.asPutDataRequest();
        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "【手機成功寫入數據】路徑: " + dataItem.getUri().getPath()))
                .addOnFailureListener(e -> Log.e(TAG, "【手機寫入失敗】", e));
    }

    // 🎯 核心修復：全面拋棄 MessageClient，連線檢測與握手全部改為 DataClient 專用通道
    private void forceHandshake() {
        Log.d(TAG, "開始執行手機與手錶的連線驗證檢測...");
        PutDataMapRequest dataMap = PutDataMapRequest.create("/dnd_state/handshake");
        dataMap.getDataMap().putLong("handshake_time", System.currentTimeMillis());
        dataMap.getDataMap().putString("sender", "phone");
        
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent(); 
        
        Wearable.getDataClient(this).putDataItem(request)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "【連線檢測】成功寫入驗證信號到 DataLayer"))
                .addOnFailureListener(e -> Log.e(TAG, "【連線檢測】寫入驗證信號失敗", e));
    }
}
