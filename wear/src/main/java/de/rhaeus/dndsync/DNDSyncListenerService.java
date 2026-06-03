package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

                // ⚙️ 業務 A：勿擾模式與睡眠模式聯動（🔴 有進有出，與省電模式徹底解綁）
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    boolean useBedtimeMode = prefs.getBoolean("bedtime_key", true); // 讀取就寢同步開關

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int filterState = mNotificationManager.getCurrentInterruptionFilter();
                        
                        if (dndValue != filterState) {
                            // 先同步手錶本地的原生勿擾狀態
                            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                isInternalUpdate = true;
                                mNotificationManager.setInterruptionFilter(dndValue);
                                mainHandler.postDelayed(() -> isInternalUpdate = false, 2000);
                            }

                            // 執行有進有出的睡眠模式自動化手勢開關
                            if (useBedtimeMode) {
                                if (dndValue > 1) {
                                    Log.d(TAG, "🌙 🟢 手機開啟勿擾 -> 手錶自動化執行：【進入】睡眠模式連招");
                                    executeAsynchronousSwipeAndClick("進入睡眠模式");
                                } else {
                                    Log.d(TAG, "☀️ 🔴 手機關閉勿擾 -> 手錶自動化執行：【退出】睡眠模式連招");
                                    executeAsynchronousSwipeAndClick("退出睡眠模式");
                                }
                            }
                        }
                    }
                }

                // ⚙️ 業務 B：手機省電模式關聯本尊（🟢 真正關聯手錶底層，不干涉睡眠手勢）
                if ("phone_power_status".equalsIgnoreCase(type)) {
                    boolean isPhonePowerSaveOn = json.optBoolean("isPhonePowerSaveOn", false);
                    Log.d(TAG, "🔌 [省電同步] 收到手機省電狀態 = " + isPhonePowerSaveOn + "，開始修改手錶原生 low_power");
                    setWearPowerSaveMode(isPhonePowerSaveOn);
                }

                // ⚙️ 業務 C：鬧鐘聯動
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        startLoopVibration();
                        Intent dialogIntent = new Intent(this, WearAlarmActivity.class);
                        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(dialogIntent);
                    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "處理資料封包異常", e);
            }
        }
    }

    /**
     * 🚀 異步無障礙連招沙盒：無論是進入還是退出，手勢完全一樣（代點 40% 反轉開關狀態）
     */
    private void executeAsynchronousSwipeAndClick(String tips) {
        final DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.w(TAG, "⚠️ 無障礙服務未激活，跳過手勢連招。");
            return;
        }

        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dndsync:WLock");
            wakeLock.acquire(3000L); // 點亮螢幕
        }

        mainHandler.post(() -> Toast.makeText(getApplicationContext(), tips + "中...", Toast.LENGTH_SHORT).show());

        // 1. 下拉控制中心
        mainHandler.postDelayed(serv::swipeDown, 400);

        // 2. 點擊 40% 圖標（進入時點擊激活，退出時點擊關閉）
        mainHandler.postDelayed(serv::clickIcon1_2, 1200);

        // 3. 收起面板返回桌面
        mainHandler.postDelayed(serv::goBack, 2000);
    }

    /**
     * 修改手錶底層原生低功耗省電狀態
     */
    private void setWearPowerSaveMode(boolean enable) {
        try {
            int target = enable ? 1 : 0;
            boolean success = Settings.Global.putInt(getContentResolver(), "low_power", target);
            Log.d(TAG, "✅ [手錶底層原生控制] low_power 修改狀態: " + success + "，當前值: " + target);
        } catch (Exception e) {
            Log.e(TAG, "❌ 寫入手錶底層省電狀態失敗", e);
        }
    }

    private void startLoopVibration() {
        try {
            if (globalVibrator == null) globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000};
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    globalVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {}
    }

    public static void stopLoopVibration() {
        if (globalVibrator != null) globalVibrator.cancel();
    }
}
