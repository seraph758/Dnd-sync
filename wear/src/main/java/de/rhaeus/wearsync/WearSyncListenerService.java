package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 手勢內部宏防併發鎖
    private static boolean isGestureMacroRunning = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            // 🎯【痛点一对齐：相机控制唤醒拦截区】
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "⌚ 手表后台收到手机端相机控制信令 Action: " + action);
                if ("START_CAMERA".equals(action)) {
                    // 🚀 瞬间拉起手表端写好的相机取景 Activity
                    Intent startIntent = new Intent(this, WearCameraActivity.class);
                    startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                       | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                                       | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(startIntent);
                    Log.d(TAG, "🚀 已成功强行弹出 WearCameraActivity 画面");
                }
                return; // 拦截处理，不向下延伸，严密保护下方原有逻辑
            }

            // 1️⃣ 勿擾/就寢/省電 同步區 (100% 原始保留，不刪改任何一個字)
            if ("dnd".equalsIgnoreCase(type)) {
                int dndStatePhone = json.optInt("dnd_profile_value", -1);
                int score = json.optInt("switches_mask", 0); // 手機發來的同步配置掩碼

                if (dndStatePhone == -1) return;

                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (mNotificationManager == null) return;

                // 🎯 精準拆解：用戶在手機 UI 上到底啟用了哪些「同步選項」
                boolean isSleepSyncEnabled   = (score & 1) != 0; // 第一位：是否勾選了「就寢同步」
                boolean isPowerSyncEnabled   = (score & 2) != 0; // 第二位：是否勾選了「省電同步」
                boolean isVibrateEnabled     = (score & 4) != 0; // 第三位：是否勾選了「震動提示」

                // ---------------------------------------------------------------------
                // 🔋 【省電同步選項的真實動作】
                // ---------------------------------------------------------------------
                if (isPowerSyncEnabled) {
                    try {
                        boolean phoneExpectsDndOn = (dndStatePhone == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                                     dndStatePhone == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                                     dndStatePhone == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                        
                        if (phoneExpectsDndOn) {
                            Log.d(TAG, "🔋 [省電同步激活] 手機開啟了就寢，%e6錶跟隨底層強制開啟省電模式");
                            Settings.Global.putInt(getContentResolver(), "low_power", 1);
                        } else {
                            Log.d(TAG, "🔌 [省電同步激活] 手機關閉了就寢，手錶跟隨底層強制關閉省電模式");
                            Settings.Global.putInt(getContentResolver(), "low_power", 0);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "🚨 省電模式底層寫入失敗", e);
                    }
                } else {
                    Log.d(TAG, "🛡️ [省電同步關閉] 檢測到未開啟省電同步選項，手錶不對省電模式做任何操作。");
                }

                // ---------------------------------------------------------------------
                // 🛌 【原本跑得很完美的勿擾/就寢手勢宏區域，百分之百保留】
                // ---------------------------------------------------------------------
                int currentDndState = mNotificationManager.getCurrentInterruptionFilter();
                boolean phoneExpectsDndOn = (dndStatePhone == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                boolean wearLocalDndIsOn = (currentDndState == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                            currentDndState == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                            currentDndState == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                if (phoneExpectsDndOn != wearLocalDndIsOn) {
                    Log.d(TAG, "🔄 勿擾狀態不一致！執行物理手勢校准。");

                    if (isVibrateEnabled) {
                        vibrateShort();
                    }

                    if (isSleepSyncEnabled) {
                        executePhysicalBedtimeMacro();
                    }

                    if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                        mNotificationManager.setInterruptionFilter(dndStatePhone);
                    }
                } else {
                    Log.d(TAG, "🛡️ [防錯位攔截] 勿擾狀態已對齊，攔截物理下拉手勢，防止反向翻轉錯位。");
                }
                return;
            }

            // ===================================================================================
            // === [🔥 LOCKED_FIREWALL: ALARM_MODULE_WEAR_UI_LAUNCH_FIREWALL - START] ===
            // 🚨 鬧鐘核心代碼嚴密保護，包名修復後依然完好如初，絕不允許被污染或改動！
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }
            // === [🔥 LOCKED_FIREWALL: ALARM_MODULE_WEAR_UI_LAUNCH_FIREWALL - END] ===
            // ===================================================================================

        } catch (Exception e) { Log.e(TAG, "流解析异常", e); }
    }

    private void executePhysicalBedtimeMacro() {
        if (isGestureMacroRunning) return;
        new Thread(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) return;
            PowerManager.WakeLock wakeLock = null;
            try {
                isGestureMacroRunning = true;
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                    wakeLock.acquire(8000L);
                }
                Thread.sleep(2000);
                serv.swipeDown();
                Thread.sleep(1000);
                serv.clickIcon1_2();
                Thread.sleep(800);
                serv.goBack();
            } catch (InterruptedException e) {
                Log.e(TAG, "手勢宏線程中斷", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                isGestureMacroRunning = false;
            }
        }).start();
    }

    private void vibrateShort() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
