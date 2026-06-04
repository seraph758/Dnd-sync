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
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                if (!"phone".equals(sender)) return;

                String type = json.optString("type", "");

                // 勿扰控制逻辑
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
                                
                                // 触发配合旧版点击拉流的线程
                                triggerSleepModeClickThread(dndState);
                            }
                        }
                    }

                    // 省电模式连动
                    if (wearPowerSave) {
                        setLowPowerMode(true);
                    } else {
                        setLowPowerMode(false);
                    }

                    // 震动提示
                    if (wearVibrate) {
                        triggerSingleVibration();
                    }
                }
                
                // 相机远程唤醒通道逻辑
                else if ("camera_control".equals(type)) {
                    String action = json.optString("action", "");
                    if ("FORCE_WAKEUP_ACTIVITY".equals(action)) {
                        Log.d(TAG, "Received camera wakeup signal from phone.");
                        
                        // 使用最高优先级权限强行点亮屏幕再拉起界面
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            PowerManager.WakeLock wl = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                                "WearSync:ServiceForceWakeUp"
                            );
                            wl.acquire(3000); // 强行点亮 3 秒确保过渡
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
                // 這裡保留您本機中與舊程式碼配合完美的下滑及點擊流
                Log.d(TAG, "Execution of automated pull-down gesture started for state: " + dndState);
                
                // 執行您原本舊版具體的下滑點擊流代碼即可
                
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
