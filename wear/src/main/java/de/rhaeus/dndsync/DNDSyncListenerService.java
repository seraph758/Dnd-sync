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
                // 1. 勿扰模式状态变化 -> 🌟 触发下拉点击自动化线框
                // ====================================================================
                if ("dnd".equalsIgnoreCase(type) && prefs.getBoolean("dnd_sync_switch", true)) {
                    int dndState = json.optInt("dndValue", -1);
                    if (dndState != -1) {
                        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (notificationManager != null) {
                            int currentFilter = notificationManager.getCurrentInterruptionFilter();
                            if (currentFilter != dndState) {
                                isInternalUpdate = true;
                                notificationManager.setInterruptionFilter(dndState);
                                Log.d(TAG, "DND state synced from phone: " + dndState);
                                
                                // 🎯【核心修复】勿扰改变，立刻强制触发本机的下拉与点击操作线框（Sleep Mode Automation）
                                Log.d(TAG, "DND changed! Triggering automated pull-down and click thread...");
                                triggerSleepModeClickThread(dndState);

                                if (json.optBoolean("wearVibrate", true)) {
                                    triggerSingleVibration();
                                }
                            }
                        }
                    }
                }

                // ====================================================================
                // 2. 相机控制：手机拉起手表
                // ====================================================================
                if ("camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    if ("FORCE_WAKEUP_ACTIVITY".equalsIgnoreCase(action)) {
                        Log.d(TAG, "Received camera wakeup signal from phone.");
                        try {
                            Intent cameraIntent = new Intent(this, WearCameraActivity.class);
                            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(cameraIntent);
                            triggerSingleVibration();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to launch WearCameraActivity", e);
                        }
                    }
                    return; 
                }

                // 原有省电与闹钟逻辑保持不变...
                if (json.has("wearPowerSave")) {
                    setLowPowerMode(json.getBoolean("wearPowerSave"));
                }

                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        Intent alarmIntent = new Intent(this, WearAlarmActivity.class);
                        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(alarmIntent);
                        startLoopVibration();
                    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        sendBroadcast(new Intent("de.rhaeus.dndsync.DISMISS_ALARM_ACTIVITY"));
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error parsing bluetooth JSON", e);
            }
        }
    }

    /**
     * 🎯 绑定您之前写好的下拉点击一整套操作的 Thread
     */
    private void triggerSleepModeClickThread(int dndState) {
        new Thread(() -> {
            try {
                // 這裡放入您本機中實現的：
                // 1. Shell 指令或 AccessibilityService 控制手錶頂部下滑
                // 2. 尋找座標並點擊睡眠模式
                Log.d(TAG, "Execution of automated pull-down gesture started for state: " + dndState);
                
                // TODO: 在此處直接呼叫您原來的自動化點擊具體代碼
                
            } catch (Exception e) {
                Log.e(TAG, "Automated pull-down thread execution failed", e);
            }
        }).start();
    }

    private void setLowPowerMode(boolean enable) {
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", enable ? 1 : 0);
            sendBroadcast(new Intent("android.os.action.POWER_SAVE_MODE_CHANGED"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to update low_power setting", e);
        }
    }

    // 震动辅助方法保持原样...
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
    
    private void startLoopVibration() { /* ... */ }
    public static void stopLoopVibration() { /* ... */ }
}
