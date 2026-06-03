package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
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
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Vibrator globalVibrator = null;

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

                // 過濾手錶端自己發出的訊息
                if ("wear".equalsIgnoreCase(sender)) return;

                // ⚙️ 業務 A：處理勿擾與手錶睡眠模式自動化（當手機勿擾改變時）
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);

                    NotificationManager mNotificationManager = (NotificationManager) 
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        
                        // 狀態去重防抖鎖
                        if (dndValue == currentFilter) {
                            Log.d(TAG, "勿擾狀態無變更，防抖鎖攔截。");
                            return; 
                        }

                        isInternalUpdate = true;
                        mNotificationManager.setInterruptionFilter(dndValue);
                        handler.postDelayed(() -> isInternalUpdate = false, 2000);
                        
                        if (wearVibrate) {
                            triggerSingleVibration();
                        }

                        // 🚀 當進入勿擾/睡眠狀態，且手機端開啟了手錶省電響應
                        if (wearPowerSave && dndValue > 1) {
                            Log.d(TAG, "🔥 勿擾連動：啟動手錶自動化優化劇本...");
                            
                            // 1️⃣ 原生控制：直接利用底層 Secure Settings 靜默開啟手錶省電模式（替代舊的80%盲點）
                            setWearPowerSaveMode(true);

                            // 2️⃣ 無障礙接力：呼叫您最初版本的自動化核心展開控制中心並點擊睡眠開關
                            final DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                            if (accessService != null) {
                                Log.d(TAG, "🎯 [系統原生下拉] 調用 openQuickSettings()...");
                                accessService.openQuickSettings(); // 🌟 核心：調用最初版的系統原生秒開控制中心

                                // 延時 600 毫秒（等待下拉動畫完全鋪滿），再執行 40% 高度的點擊
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        Log.d(TAG, "🎯 [無障礙點擊] 動畫結束，精準點擊 40% 睡眠模式按鈕");
                                        accessService.clickIcon1_2(); // 🌟 核心：調用最初版的 40% 高度點擊
                                    }
                                }, 600);
                            } else {
                                Log.w(TAG, "⚠️ 輔助功能服務未連接或未開啟，跳過 40% 睡眠開關自動化");
                            }
                        }
                    }
                }

                // ⚙️ 業務 B：處理手機端省電模式同步控制（手機開省電 -> 手錶跟著開省電）
                if ("phone_power_status".equalsIgnoreCase(type)) {
                    boolean isPhonePowerSaveOn = json.optBoolean("isPhonePowerSaveOn", false);
                    Log.d(TAG, "🔌 收到手機省電狀態通知，手機省電開啟 = " + isPhonePowerSaveOn);
                    setWearPowerSaveMode(isPhonePowerSaveOn);
                }

                // ⚙️ 業務 C：處理手機鬧鐘聯動
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "⏰ 鬧鐘響鈴中... 啟動手錶持續震動並強行拉起全螢幕交互頁面。");
                        startLoopVibration();
                        
                        // 🚀 核心進化：強行拉起全螢幕流光動畫 Activity 彈窗
                        Intent dialogIntent = new Intent(this, WearAlarmActivity.class);
                        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(dialogIntent);
                        
                    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "🛑 鬧鐘已終止，解除手錶震動並退出全螢幕。");
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解包處理失敗", e);
            }
        }
    }

    // 🚀 底層 Secure Settings 省電模式靜默控制方法
    private void setWearPowerSaveMode(boolean enable) {
        try {
            int targetValue = enable ? 1 : 0;
            int currentMode = Settings.Global.getInt(getContentResolver(), "low_power", 0);
            if (currentMode == targetValue) {
                Log.d(TAG, "手錶省電狀態已為 " + targetValue + "，跳過重複寫入。");
                return;
            }

            boolean success = Settings.Global.putInt(getContentResolver(), "low_power", targetValue);
            if (success) {
                Log.d(TAG, "✅ [原生控制] 成功修改手錶底層省電狀態 [low_power = " + targetValue + "]");
            } else {
                Log.e(TAG, "❌ [原生控制] 修改 low_power 失敗");
            }
        } catch (SecurityException se) {
            Log.e(TAG, "❌ [權限不足] 請在電腦終端機執行：adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
        } catch (Exception e) {
            Log.e(TAG, "設置省電模式異常", e);
        }
    }

    private void startLoopVibration() {
        try {
            if (globalVibrator == null) {
                globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000}; 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    globalVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "迴圈震動異常", e);
        }
    }

    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "停震異常", e);
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
            Log.e(TAG, "單次震動異常", e);
        }
    }
}
