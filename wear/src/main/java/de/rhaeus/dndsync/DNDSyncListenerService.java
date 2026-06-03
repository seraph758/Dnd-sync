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

    private static final String PATH_PHONE_TO_WEAR = "/dnd_state/phone_to_wear";
    private static final String PATH_HANDSHAKE = "/dnd_state/handshake";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "====== 🔥🔥🔥 onDataChanged 被系統呼叫！事件數量: " + dataEvents.getCount() + " ======");

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();
                
                Log.d(TAG, "📥 收到 DataItem 路徑: " + (path != null ? path : "null"));

                if (path == null) continue;

                if (PATH_HANDSHAKE.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String sender = dataMap.getString("sender", "");
                    Log.d(TAG, "✅ 收到握手信號，發送者: " + sender);
                    continue;
                }

                if (PATH_PHONE_TO_WEAR.equals(path)) {
                    Log.d(TAG, "🎯 收到手機端完整的 DND 同步資料！");
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    
                    int rawDndValue = dataMap.getInt("raw_dnd_value", 1);
                    boolean dndSyncSwitch = dataMap.getBoolean("dnd_sync_switch", true);
                    boolean wearPowerSaveResponse = dataMap.getBoolean("wear_power_save_response", false);
                    boolean wearVibrateOnSync = dataMap.getBoolean("wear_vibrate_on_sync", true);

                    Log.d(TAG, "📊 資料內容 | rawDND=" + rawDndValue + " | 同步開關=" + dndSyncSwitch 
                            + " | 省電響應=" + wearPowerSaveResponse + " | 震動=" + wearVibrateOnSync);

                    if (!dndSyncSwitch) {
                        Log.d(TAG, "同步總開關已關閉，跳過");
                        continue;
                    }

                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        int current = nm.getCurrentInterruptionFilter();
                        if (rawDndValue != current) {
                            isInternalUpdate = true;
                            nm.setInterruptionFilter(rawDndValue);
                            Log.d(TAG, "✅ 手錶 DND 已成功設定為: " + rawDndValue);
                            handler.postDelayed(() -> {
                                isInternalUpdate = false;
                                Log.d(TAG, "內部更新標記已解除");
                            }, 2000);
                        } else {
                            Log.d(TAG, "DND 狀態相同，無需變更");
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
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(200);
                }
                Log.d(TAG, "✅ 震動觸發");
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
            Log.d(TAG, "✅ 已執行省電模式自動點擊");
        } else {
            Log.w(TAG, "⚠️ DNDSyncAccessService 為 null，無法執行省電動作");
        }
    }
}