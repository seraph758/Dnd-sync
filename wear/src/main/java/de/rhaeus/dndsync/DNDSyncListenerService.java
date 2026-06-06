package de.rhaeus.dndsync;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private static Vibrator globalVibrator = null;

    @Override
    public void onCreate() {
        super.onCreate();
        if (globalVibrator == null) {
            globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel();
                Log.d(TAG, "🛑 收到停止持續震動指令");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止震動異常", e);
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                Log.d(TAG, "📥 手錶端收到高優先級互聯數據包: " + jsonStr);

                try {
                    JSONObject json = new JSONObject(jsonStr);
                    String sender = json.optString("sender", "");
                    if ("wear".equalsIgnoreCase(sender)) return;

                    String type = json.optString("type", "");

                    // 🎯 A. 鬧鐘處理
                    if ("alarm".equalsIgnoreCase(type)) {
                        String alarmAction = json.optString("alarmAction", "");
                        if ("LAUNCH_WEAR_ALARM_ACTIVITY".equalsIgnoreCase(alarmAction)) {
                            Intent alarmIntent = new Intent();
                            alarmIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearAlarmActivity");
                            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(alarmIntent);
                        } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(alarmAction)) {
                            Intent stopBroadcast = new Intent("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI");
                            sendBroadcast(stopBroadcast);
                            stopLoopVibration();
                        }
                        return;
                    }

                    // 🎯 B. 相機處理
                    if ("camera_control".equalsIgnoreCase(type)) {
                        String action = json.optString("action", "");
                        if ("LAUNCH_WEAR_CAMERA_ACTIVITY".equalsIgnoreCase(action)) {
                            Intent cameraIntent = new Intent();
                            cameraIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearCameraActivity");
                            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(cameraIntent);
                        }
                        return;
                    }

                    // 🎯 C. 勿擾和強依賴樹
                    if ("dnd".equalsIgnoreCase(type)) {
                        int dndState = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                        boolean dndMaster = json.optBoolean("dndSyncMaster", true);
                        boolean wearSleepLink = json.optBoolean("wearSleepModeLink", true);
                        boolean wearPowerLink = json.optBoolean("wearPowerSave", false);
                        boolean vibrateTipsEnable = json.optBoolean("vibrateTipsEnable", true);

                        if (!dndMaster) return;

                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) {
                            isInternalUpdate = true;
                            nm.setInterruptionFilter(dndState);
                        }

                        if (vibrateTipsEnable && globalVibrator != null) {
                            globalVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                        }

                        // 🎯 睡眠模式同步連動
                        if (wearSleepLink) {
                            boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                            executeBedtimeToggleUiMacro(isDndActive);
                        }

                        if (wearPowerLink) {
                            boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                            try {
                                Settings.Global.putInt(getContentResolver(), "low_power", isDndActive ? 1 : 0);
                                sendBroadcast(new Intent("android.os.action.POWER_SAVE_MODE_CHANGED"));
                            } catch (Exception e) {}
                        }
                        return;
                    }

                } catch (Exception jsonEx) {
                    String valStr = jsonStr.trim();
                    int dndVal = Integer.parseInt(valStr);
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) { nm.setInterruptionFilter(dndVal); }
                }

            } catch (Exception e) {
                Log.e(TAG, "穿戴消息處理崩潰：", e);
            }
        }
    }

    private void executeBedtimeToggleUiMacro(boolean targetActive) {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "無障礙服務未連接，放棄自動切換");
            return;
        }

        new Thread(() -> {
            try {
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:WakeLock");
                wakeLock.acquire(10 * 1000L);

                Thread.sleep(800);
                
                // 🎯 核心修正：拋棄老舊滑動，直接向 Android 核心注入底層巨集指令，強制展開快捷設置控制面板
                serv.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                
                Thread.sleep(1000);
                serv.clickIcon1_2();    // 模擬觸控睡眠圖標
                Thread.sleep(800);
                serv.goBack();          // 強制回退收起快捷面板
                
                if (wakeLock.isHeld()) { wakeLock.release(); }
                Log.d(TAG, "✨ 已通過系統全局快捷原語成功完成睡眠模式翻轉動作");
            } catch (Exception e) {
                Log.e(TAG, "無障礙巨集流程異常中斷", e);
            }
        }).start();
    }
}
