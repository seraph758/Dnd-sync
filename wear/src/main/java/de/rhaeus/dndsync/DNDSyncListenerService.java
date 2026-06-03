package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
    
    // 同時兼容新版 JSON 通道與舊版通道
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    public static boolean isInternalUpdate = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Vibrator globalVibrator = null;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        Log.d(TAG, "📥 收到手錶數據路徑: " + path);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // 🟢 情況 A：處理全新的全渠道通用 JSON 協議 (包含鬧鐘、新勿擾等)
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(path)) {
            byte[] data = messageEvent.getData();
            if (data == null) return;

            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                if ("wear".equalsIgnoreCase(sender)) return; // 過濾手錶自己發出的

                // 1️⃣ 勿擾模式與自動化下拉點擊連動
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean useBedtimeMode = json.optBoolean("wearPowerSave", false); // 復用開關控制就寢自動化
                    boolean vibrate = json.optBoolean("wearVibrate", true);

                    handleDndSyncLogic(dndValue, useBedtimeMode, vibrate);
                }

                // 2️⃣ 鬧鐘聯動：啟動震動並拉起全屏交互動畫 Activity
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "⏰ 鬧鐘響鈴中... 啟動持續震動並強行彈出全屏 Activity。");
                        startLoopVibration();
                        
                        // 拉起全屏鬧鐘動畫介面
                        Intent dialogIntent = new Intent(this, WearAlarmActivity.class);
                        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(dialogIntent);
                        
                    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "🛑 鬧鐘已終止，解除手錶震動。");
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析通用 JSON 異常", e);
            }

        // 🔵 情況 B：處理最原始的 byte[] 勿擾協議 (保持老版本兼容性)
        } else if (DND_SYNC_MESSAGE_PATH.equalsIgnoreCase(path)) {
            byte[] data = messageEvent.getData();
            if (data == null || data.length == 0) return;

            int dndStatePhone = data[0];
            boolean useBedtimeMode = prefs.getBoolean("bedtime_key", true);
            boolean vibrate = prefs.getBoolean("vibrate_key", false);

            handleDndSyncLogic(dndStatePhone, useBedtimeMode, vibrate);
            
        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    /**
     * 核心勿擾同步與無障礙下拉點擊調度沙盒
     */
    private void handleDndSyncLogic(int dndStatePhone, boolean useBedtimeMode, boolean vibrate) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager == null) return;

        int filterState = mNotificationManager.getCurrentInterruptionFilter();
        if (dndStatePhone != filterState) {
            
            if (vibrate) {
                triggerSingleVibration();
            }

            // 如果開啟了就寢同步，啟動全新無障礙異步連招（肉眼可見下拉點擊）
            if (useBedtimeMode && dndStatePhone > 1) {
                executeAsynchronousSwipeAndClick();
            }

            // 同步修改手錶本地勿擾狀態
            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                isInternalUpdate = true;
                mNotificationManager.setInterruptionFilter(dndStatePhone);
                mainHandler.postDelayed(() -> isInternalUpdate = false, 2000);
                Log.d(TAG, "手錶 DND 成功設置為: " + dndStatePhone);
            }
        }
    }

    /**
     * 🚀 核心優化：利用 Handler 異步連招，完美替代原有 Thread.sleep 鎖死問題
     */
    private void executeAsynchronousSwipeAndClick() {
        final DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "⚠️ 警告：無障礙服務未連接！");
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "同步核心未激活，請在手錶系統輔助功能中開啟", Toast.LENGTH_LONG).show());
            return;
        }

        Log.d(TAG, "🎯 [無障礙核心激活] 開始執行異步下拉與點擊連招...");

        // 1. 點亮屏幕（保留原版 WakeLock 精髓）
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dndsync:MyWakeLock");
            wakeLock.acquire(5000L); // 點亮 5 秒足夠完成動作
        }

        // 2. 彈出 Toast 提示
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), "正在同步就寝模式...", Toast.LENGTH_SHORT).show());

        // 3. 【動作一】：立即下拉菜單（主線程非阻塞執行）
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "🎯 [連招 1/3] 執行手錶屏幕 swipeDown()...");
                serv.swipeDown(); // 調用最初版的滑動下拉
            }
        }, 500); // 給屏幕亮起預留 500ms

        // 4. 【動作二】：等待面板完全滑出後，精準點擊 40% 高度的睡眠模式
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "🎯 [連招 2/3] 面板就位，精準執行 clickIcon1_2()...");
                serv.clickIcon1_2(); // 調用最初版不帶參數的 40% 點擊
            }
        }, 1300); // 在下拉 800ms 後準時點擊，防快點點空

        // 5. 【動作三】：點擊完成後，自動返回桌面收起面板
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "🎯 [連招 3/3] 動作完成，執行 goBack() 收起面板...");
                serv.goBack(); 
            }
        }, 2200); 
    }

    // ======= 鬧鐘循環震動輔助模塊 =======
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
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(50);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "單次震動異常", e);
        }
    }
}
