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

    // 統一路徑
    private static final String PATH_PHONE_TO_WEAR = "/dnd_state/phone_to_wear";
    private static final String PATH_HANDSHAKE = "/dnd_state/handshake";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();
                
                Log.d(TAG, "手錶收到資料: " + path);

                if (path == null) continue;

                if (PATH_HANDSHAKE.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String sender = dataMap.getString("sender", "");
                    if ("phone".equals(sender)) {
                        Log.d(TAG, "✅ 手錶收到手機握手 → 雙向連線正常");
                    }
                    continue;
                }

                if (PATH_PHONE_TO_WEAR.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    int rawDndValue = dataMap.getInt("raw_dnd_value", 1);
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", true);

                    Log.d(TAG, "✅ 完整設定簽收 | DND=" + rawDndValue + " | 省電=" + wearPowerSaveResponse + " | 震動=" + wearVibrateOnSync);

                    if (!dndSyncSwitch) continue;

                    // 執行 DND 同步
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        int current = nm.getCurrentInterruptionFilter();
                        if (rawDndValue != current) {
                            isInternalUpdate = true;
                            nm.setInterruptionFilter(rawDndValue);
                            handler.postDelayed(() -> isInternalUpdate = false, 2000);
                        }
                    }

                    if (wearVibrateOnSync) triggerWatchVibration();
                    if (wearPowerSaveResponse) triggerPowerSaveAction();
                }
            }
        }
    }

    private void triggerWatchVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "震動失敗", e);
        }
    }

    private void triggerPowerSaveAction() {
        DNDSyncAccessService service = DNDSyncAccessService.getSharedInstance();
        if (service != null) {
            service.clickIconAt80Percent(0);
            service.clickIcon1_2(200);
            Log.d(TAG, "已觸發省電模式自動點擊");
        } else {
            Log.w(TAG, "DNDSyncAccessService 為 null，無法執行省電動作");
        }
    }
}