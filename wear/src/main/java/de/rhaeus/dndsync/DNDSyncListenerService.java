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
    
    // 關鍵修復：還原全局靜態震動器與上下文，供外部分支 Activity 調用停止
    private static Vibrator globalVibrator = null;
    private static Context serviceContext = null;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceContext = getApplicationContext();
        if (globalVibrator == null) {
            globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private SharedPreferences getDndSyncPreferences() {
        return getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    // 關鍵修復：還原外部 DNDNotificationService 和 WearAlarmActivity 強烈依賴的靜態解鎖方法
    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                globalVibrator.cancel();
                Log.d(TAG, "⚡ 外部調用成功：已強制終止手錶端所有循環震動狀態");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel loop vibration from static context", e);
        }
    }

    public static void startLoopVibration(long[] pattern, int repeat) {
        try {
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat));
                } else {
                    globalVibrator.vibrate(pattern, repeat);
                }
                Log.d(TAG, "⚡ 循環震動成功開啟");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start loop vibration", e);
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                if (!"phone".equals(sender)) return;

                String type = json.optString("type", "");

                // 1. 勿扰连动控制
                if ("dnd".equals(type)) {
                    int dndState = json.getInt("dndValue");
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);

                    SharedPreferences prefs = getDndSyncPreferences();
                    boolean isDndSyncEnabled = prefs.getBoolean("dnd_sync_switch", true);

                    if (isDndSyncEnabled) {
                        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (mNotificationManager != null) {
                            int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                            if (currentFilter != dndState) {
                                isInternalUpdate = true;
                                mNotificationManager.setInterruptionFilter(dndState);
                                Log.d(TAG, "DND state synced smoothly to: " + dndState);
                                
                                triggerSleepModeClickThread(dndState);
                            }
                        }
                    }

                    if (wearPowerSave) {
                        setLowPowerMode(true);
                    } else {
                        setLowPowerMode(false);
                    }

                    if (wearVibrate) {
                        triggerSingleVibration();
                    }
                }
                
                // 2. 鬧鐘遠端指令控制（整合舊版：關鍵字關閉與臨時暫停功能）
                else if ("alarm_control".equals(type)) {
                    String alarmAction = json.optString("action", "");
                    Log.d(TAG, "收到手機端鬧鐘同步指令: " + alarmAction);
                    
                    Intent alarmIntent = new Intent("de.rhaeus.dndsync.ALARM_TRIGGER");
                    alarmIntent.putExtra("action_type", alarmAction);
                    
                    // 發送廣播通知本地的 WearAlarmActivity 或 DNDNotificationService 執行響鈴或停止
                    sendBroadcast(alarmIntent);
                }
                
                // 3. 相机控制远程拉起通道
                else if ("camera_control".equals(type)) {
                    String action = json.optString("action", "");
                    if ("FORCE_WAKEUP_ACTIVITY".equals(action)) {
                        Log.d(TAG, "Received camera wakeup signal from phone.");
                        
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            PowerManager.WakeLock wl = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                                "WearSync:ServiceForceWakeUp"
                            );
                            wl.acquire(3000);
                        }

                        Intent dialogIntent = new Intent(this, WearCameraActivity.class);
                        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(dialogIntent);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to parse synchronized flow packet", e);
            }
        }
    }
    private void triggerSleepModeClickThread(int dndState) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Execution of automated pull-down gesture started for state: " + dndState);
                // 在此處直接執行您原本舊版具體的下滑點擊流代碼
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

    private void triggerSingleVibration() {
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
            Log.e(TAG, "Vibration feedback failed", e);
        }
    }
}
