package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {

    private static final String TAG = "DNDSyncListener";

    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    
    // 🎯 對接手機端全新的 Message 通路
    private static final String SETTINGS_MSG_PATH = "/settings-sync-msg";
    private static final String CMD_DND_SETTING = "/open-wear-dnd-setting";
    private static final String CMD_ACC_SETTING = "/open-wear-acc-setting";
    private static final String CMD_TEST_CLICK = "/test-wear-click";

    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 4000;

    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler();

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        byte[] data = messageEvent.getData();

        Log.d(TAG, "手錶收到 Message 管道訊息, Path = " + path);

        // 🎯 1. 攔截設定組態訊息，精準更新本地快遞緩存
        if (SETTINGS_MSG_PATH.equalsIgnoreCase(path)) {
            if (data != null && data.length >= 3) {
                boolean dndSync = data[0] == 1;
                boolean powerSave = data[1] == 1;
                boolean wearPowerSave = data[2] == 1;

                // 核心規則：手機省電和手錶聯動優化同時打開
                boolean isPowerSaveEnabledOnPhone = powerSave && wearPowerSave;

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit()
                     .putBoolean("phone_wear_power_save_state", isPowerSaveEnabledOnPhone)
                     .putBoolean("dnd_sync_switch", dndSync)
                     .apply();
                     
                Log.d(TAG, "【核心成功】手錶已精準緩存聯動狀態為: " + isPowerSaveEnabledOnPhone);
            }
            return;
        }

        // 🎯 2. 接收手機 UI 移過來的遠端跳轉指令：開啟勿擾權限
        if (CMD_DND_SETTING.equalsIgnoreCase(path)) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }

        // 🎯 3. 接收手機 UI 移過來的遠端跳轉指令：開啟無障礙服務
        if (CMD_ACC_SETTING.equalsIgnoreCase(path)) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }

        // 🎯 4. 接收手機 UI 移過來的遠端跳轉指令：直接手動測試模擬點擊劇本
        if (CMD_TEST_CLICK.equalsIgnoreCase(path)) {
            Log.d(TAG, "收到手機端遠端測試指令，直接空降執行點擊劇本");
            toggleBedtimeMode();
            return;
        }

        // 🎯 5. 處理標準勿擾狀態同步邏輯
        if (DND_SYNC_MESSAGE_PATH.equalsIgnoreCase(path)) {
            // 檢查手機端是否開啟了雙向同步開關
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("dnd_sync_switch", true)) {
                Log.d(TAG, "手機端勿擾同步總開關已關閉，跳過狀態變更");
                return;
            }

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecutionTime < COOLDOWN_MS) {
                Log.d(TAG, "還在冷卻期，忽略本次信號");
                return;
            }
            lastExecutionTime = currentTime;

            if (data == null || data.length == 0) return;
            int dndState = data[0];
            applyDndState(dndState);
        }
    }

    private void applyDndState(int dndState) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager != null) {
            int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
            if (currentFilter != dndState) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    isInternalUpdate = true;
                    mNotificationManager.setInterruptionFilter(dndState);
                    Log.d(TAG, "成功設置手錶勿擾狀態為: " + dndState);
                    
                    // 呼叫自動點擊
                    toggleBedtimeMode();
                    
                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                }
            }
        }
    }

    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "AccessibilityService 無障礙服務尚未連接！請檢查權限");
            return;
        }

        new Thread(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dnd:sync");
                wakeLock.acquire(5000L);
                
                Thread.sleep(800);

                // 1. 下拉控制中心
                serv.swipeDown();
                Log.d(TAG, "執行手勢下拉...");
                
                // 🎯 關鍵等待：等待控制中心菜單完全滑下並定格（1.4秒確保穩定）
                Thread.sleep(1400);

                // 2. 判定儲存的省電優化狀態
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean isPhonePowerSaveOpen = prefs.getBoolean("phone_wear_power_save_state", false);

                if (isPhonePowerSaveOpen) {
                    Log.d(TAG, "【執行劇本 A】雙連擊防吞模式啟動！");
                    
                    // 🎯 先發射點擊 80% 高度（延遲 250ms 確保按鈕進入可點擊活躍態）
                    serv.clickIconAt80Percent(250);
                    
                    // 🎯 再發射點擊 40% 高度（在 750ms 時觸發，中間預留 500ms 給系統響應彈窗）
                    serv.clickIcon1_2(750);
                    
                    Thread.sleep(1200); 
                } else {
                    Log.d(TAG, "【執行劇本 B】常規單點 40% 高度模式");
                    serv.clickIcon1_2(250);
                    Thread.sleep(600);
                }

                // 3. 返回桌面
                serv.goBack();

            } catch (Exception e) {
                Log.e(TAG, "toggleBedtimeMode 劇本異常", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }).start();
    }
}
