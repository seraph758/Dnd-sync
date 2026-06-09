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

            // 1️⃣ 勿擾/就寢同步區
            if ("dnd".equalsIgnoreCase(type)) {
                int dndStatePhone = json.optInt("dnd_profile_value", -1);
                int score = json.optInt("switches_mask", 0);

                if (dndStatePhone == -1) return;

                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (mNotificationManager == null) return;

                // 獲取手錶本地真實的當前勿擾狀態
                int currentDndState = mNotificationManager.getCurrentInterruptionFilter();

                // ⚡【狀態感知防火牆】：判斷是否處於激活勿擾級別 (2=PRIORITY, 3=NONE, 4=ALARMS)
                boolean phoneExpectsDndOn = (dndStatePhone == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                             dndStatePhone == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                
                boolean wearLocalDndIsOn = (currentDndState == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                            currentDndState == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                            currentDndState == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                // 🚨 核心修復：只有在手機期望狀態與手錶本地狀態「不對齊」時，才啟動手勢宏去切換！
                if (phoneExpectsDndOn != wearLocalDndIsOn) {
                    Log.d(TAG, "🔄 兩端狀態不一致！手機期望開啟勿擾=" + phoneExpectsDndOn + " | 手錶當前=" + wearLocalDndIsOn + " -> 執行校準");
                    
                    // 執行震動反饋
                    if (score == 4 || score == 5 || score == 6 || score == 7) {
                        vibrateShort();
                    }

                    // 如果開啟了就寢同步（對應分數含有 1, 3, 5, 7）
                    if (score == 1 || score == 3 || score == 5 || score == 7) {
                        executePhysicalBedtimeMacro();
                    }

                    // 保底強制同步手錶底層的勿擾 Filter
                    if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                        mNotificationManager.setInterruptionFilter(dndStatePhone);
                    }
                } else {
                    // 🎯 狀態一致時死不動，徹底解決無限盲按、來回翻轉錯位的惡性循環
                    Log.d(TAG, "🛡️ [防錯位攔截] 手機與手錶勿擾狀態已在同一象限，拦截本次下拉盲摸手勢。");
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

    /**
     * 獨立非阻塞線程：嚴格依照舊代碼的時序執行「喚醒 -> 下滑 -> 點擊 -> 返回」
     */
    private void executePhysicalBedtimeMacro() {
        if (isGestureMacroRunning) {
            Log.w(TAG, "⚠️ 手勢宏正在執行中，拒絕併發干擾");
            return;
        }

        new Thread(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) {
                Log.e(TAG, "❌ 無障礙服務未連通，放棄執行手勢");
                return;
            }

            PowerManager.WakeLock wakeLock = null;
            try {
                isGestureMacroRunning = true;

                // 1. 物理強制點亮螢幕
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                    wakeLock.acquire(5000L); // 鎖定 5 秒完成整套動作
                }

                // 2. 核心時序緩衝：留出 800ms 讓螢幕硬體完全亮起，防止螢幕沒亮完導致下滑無效
                Thread.sleep(800);

                // 3. 呼叫舊代碼純淨下滑
                serv.swipeDown();

                // 4. 時序緩衝：留出 1000ms 給下拉選單動畫展開，防止面板還沒拉下來就去盲點
                Thread.sleep(1000);

                // 5. 點擊第一排中間圖標
                serv.clickIcon1_2();

                // 6. 時序緩衝：留出 1000ms 給系統響應就寢/睡眠狀態變更
                Thread.sleep(1000);

                // 7. 收起快捷面板
                serv.goBack();
                Log.d(TAG, "🏁 [手勢宏] 物理控制校準鏈條圓滿結束");

            } catch (InterruptedException e) {
                Log.e(TAG, "手勢宏線程中斷", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
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
