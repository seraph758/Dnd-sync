package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
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

                if ("wear".equalsIgnoreCase(sender)) return;

                // ⚙️ 業務 A：處理勿擾與手錶省電聯動（當手機勿擾改變時）
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);

                    NotificationManager mNotificationManager = (NotificationManager) 
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        
                        if (dndValue == currentFilter) {
                            Log.d(TAG, " 勿擾狀態無變更，防抖鎖攔截。");
                            return; 
                        }

                        isInternalUpdate = true;
                        mNotificationManager.setInterruptionFilter(dndValue);
                        handler.postDelayed(() -> isInternalUpdate = false, 2000);
                        
                        if (wearVibrate) {
                            triggerSingleVibration();
                        }

                        if (wearPowerSave && dndValue > 1) {
                            Log.d(TAG, "🔥 勿擾連動：觸發手錶端底層靜默開啟省電...");
                            setWearPowerSaveMode(true);

                            DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                            if (accessService != null) {
                                Log.d(TAG, "🎯 [無障礙接力] 點擊 40% 睡眠開關...");
                                accessService.clickIcon1_2(100);
                            }
                        }
                    }
                }

                // 🎯 ⚙️ 業務 B：【全新加入】處理手機端省電模式連動
                if ("phone_power_status".equalsIgnoreCase(type)) {
                    boolean isPhonePowerSaveOn = json.optBoolean("isPhonePowerSaveOn", false);
                    Log.d(TAG, "🔌 收到手機省電狀態封包，手機當前省電模式開啟 = " + isPhonePowerSaveOn);
                    
                    // 讓手錶的原生省電狀態 100% 複製手機的狀態
                    setWearPowerSaveMode(isPhonePowerSaveOn);
                }

                // ⚙️ 業務 C：處理手機鬧鐘聯動
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "⏰ 鬧鐘響鈴中... 啟動持續震動。");
                        startLoopVibration();
                    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "🛑 鬧鐘已終止，解除手錶震動。");
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解包處理失敗", e);
            }
        }
    }

    // 🚀 精簡封裝的底層 Secure Settings 省電模式靜默切換控制
    private void setWearPowerSaveMode(boolean enable) {
        try {
            int targetValue = enable ? 1 : 0;
            
            // 防重覆設置檢查（選填，Settings.Global.putInt 本身已具備去重性）
            int currentMode = Settings.Global.getInt(getContentResolver(), "low_power", 0);
            if (currentMode == targetValue) {
                Log.d(TAG, "手錶省電狀態已為 " + targetValue + "，跳過重複寫入。");
                return;
            }

            boolean success = Settings.Global.putInt(
                getContentResolver(), 
                "low_power", 
                targetValue
            );
            if (success) {
                Log.d(TAG, "✅ [原生控制] 成功經由底層代碼同步手錶省電狀態為: " + targetValue);
            } else {
                Log.e(TAG, "❌ [原生控制] 修改 low_power 失敗");
            }
        } catch (SecurityException se) {
            Log.e(TAG, "❌ [權限不足] 無法修改省電模式！請確保執行過 adb pm grant 授權！");
        } catch (Exception e) {
            Log.e(TAG, "設定省電模式異常", e);
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
