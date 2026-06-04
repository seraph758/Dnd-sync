package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private static Vibrator globalVibrator = null;
    private static Context serviceContext = null;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceContext = getApplicationContext();
    }

    // 🎯 統一手錶端命名空間，防止資料夾混亂
    private SharedPreferences getDndSyncPreferences() {
        return getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            if (data == null) return;

            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);

                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                if ("wear".equalsIgnoreCase(sender)) {
                    return; 
                }

                SharedPreferences prefs = getDndSyncPreferences();

                // ====================================================================
                // 🌟 新增分支：手機端主動「相機控制」與「強行喚醒手錶畫布」
                // ====================================================================
                if ("camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    
                    if ("FORCE_WAKEUP_ACTIVITY".equalsIgnoreCase(action)) {
                        Log.d(TAG, "🚀 收到手機端主動喚醒信號！正在拉起手錶相機畫布...");
                        try {
                            // 建立手錶端相機介面的 Intent
                            Intent cameraIntent = new Intent(this, WearCameraActivity.class);
                            // 關鍵標記：後台服務拉起 Activity 必須使用 NEW_TASK
                            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(cameraIntent);
                            
                            // 喚醒時附帶一次清脆短震提示
                            triggerSingleVibration();
                        } catch (Exception e) {
                            Log.e(TAG, "❌ 無法啟動 WearCameraActivity，請確認手錶端是否已建立該類別", e);
                        }
                    }
                    return; // 處理完畢，直接結束
                }

                // ====================================================================
                // 原有勿擾同步邏輯
                // ====================================================================
                if ("dnd".equalsIgnoreCase(type) && prefs.getBoolean("dnd_sync_switch", true)) {
                    int dndState = json.optInt("dndValue", -1);
                    if (dndState != -1) {
                        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (notificationManager != null) {
                            int currentFilter = notificationManager.getCurrentInterruptionFilter();
                            if (currentFilter != dndState) {
                                isInternalUpdate = true;
                                notificationManager.setCurrentInterruptionFilter(dndState);
                                Log.d(TAG, "成功同步手機勿擾狀態至手錶: " + dndState);
                                
                                if (json.optBoolean("wearVibrate", true)) {
                                    triggerSingleVibration();
                                }
                            }
                        }
                    }
                }

                // ====================================================================
                // 原有省電模式聯動
                // ====================================================================
                if (json.has("wearPowerSave")) {
                    boolean targetPowerSave = json.getBoolean("wearPowerSave");
                    setLowPowerMode(targetPowerSave);
                }

                // ====================================================================
                // 原有鬧鐘穿透聯動
                // ====================================================================
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "⏰ 偵測到手機鬧鐘響鈴，正在拉起手錶全螢幕通知...");
                        Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(alarmIntent);
                        startLoopVibration();
                    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "🛑 手機鬧鐘已停止，關閉手錶彈窗並停震");
                        WearAlarmActivity.dismissActivity();
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析藍牙 JSON 異常", e);
            }
        }
    }

    private void setLowPowerMode(boolean enable) {
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", enable ? 1 : 0);
            Intent intent = new Intent("android.os.action.POWER_SAVE_MODE_CHANGED");
            sendBroadcast(intent);
            Log.d(TAG, "手錶低功耗狀態更新為: " + enable);
        } catch (Exception e) {
            Log.e(TAG, "❌ 修改系統 low_power 失敗", e);
        }
    }

    public static void sendDismissToPhone() {
        if (serviceContext == null) return;
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm");
                json.put("alarmAction", "dismiss");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(serviceContext).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(serviceContext).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {}
        }).start();
    }

    private void startLoopVibration() {
        try {
            if (globalVibrator == null) globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000};
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                }
            }
        } catch (Exception e) {}
    }

    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel();
            }
        } catch (Exception e) {}
    }

    private void triggerSingleVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
        } catch (Exception e) {}
    }
}
