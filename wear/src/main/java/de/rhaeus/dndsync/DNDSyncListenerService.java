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

                // ⚙️ 業務 A：處理勿擾與省電模式自動化
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);

                    NotificationManager mNotificationManager = (NotificationManager) 
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        
                        // 🔒 狀態去重防抖鎖
                        if (dndValue == currentFilter) {
                            Log.d(TAG, " 狀態無變更，防抖鎖攔截。");
                            return; 
                        }

                        // 狀態確實改變了，執行變更
                        isInternalUpdate = true;
                        mNotificationManager.setInterruptionFilter(dndValue);
                        handler.postDelayed(() -> isInternalUpdate = false, 2000);
                        
                        if (wearVibrate) {
                            triggerSingleVibration();
                        }

                        // 🚀 【核心進化】：當進入勿擾/睡眠模式，且手機端開啟了手錶響應
                        if (wearPowerSave && dndValue > 1) {
                            Log.d(TAG, "🔥 觸發手錶端省電模式連動劇本...");
                            
                            // 1️⃣ 替代 80% 高度的動作：直接通過底層 Secure Settings 靜默開啟省電模式
                            try {
                                boolean success = Settings.Global.putInt(
                                    getContentResolver(), 
                                    "low_power", 
                                    1 // 1 代表開啟省電
                                );
                                if (success) {
                                    Log.d(TAG, "✅ [原生控制] 成功經由底層代碼靜默切換 [low_power = 1]");
                                } else {
                                    Log.e(TAG, "❌ [原生控制] 修改 low_power 失敗（未知原因）");
                                }
                            } catch (SecurityException se) {
                                Log.e(TAG, "❌ [權限爆破失敗] 缺少 WRITE_SECURE_SETTINGS 權限！");
                                Log.e(TAG, "請至電腦端執行命令: adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                            }

                            // 2️⃣ 保留 40% 高度的動作：去觸發剩下的「睡眠模式」或自定義開關
                            // 由於去掉了不穩定的 80% 下拉開關，我們此處直接呼叫無障礙去點擊 40% 介面
                            DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                            if (accessService != null) {
                                Log.d(TAG, "🎯 [無障礙接力] 執行剩餘 40% 睡眠開關自動化點擊...");
                                accessService.clickIcon1_2(100); // 直接執行原來的 40% 點擊
                            } else {
                                Log.w(TAG, "⚠️ 無障礙服務未開啟，跳過 40% 睡眠開關點擊");
                            }
                        }
                    }
                }

                // ⚙️ 業務 B：處理手機鬧鐘聯動
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
