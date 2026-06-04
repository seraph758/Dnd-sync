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
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
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

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

                // ⚙️ 業務 A：勿擾模式狀態更改（絕對控制中心）
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean useBedtimeMode = prefs.getBoolean("bedtime_key", true);
                    // 🌟 完美理解點 1：省電模式開關完全綁定並從屬於睡眠模式
                    boolean linkPowerSaveWithBedtime = prefs.getBoolean("wear_power_save_key", true);

                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int filterState = mNotificationManager.getCurrentInterruptionFilter();
                        
                        if (dndValue != filterState) {
                            // 先同步手錶本地原生勿擾
                            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                isInternalUpdate = true;
                                mNotificationManager.setInterruptionFilter(dndValue);
                                // 延時復位內部更新標記
                                new Thread(() -> {
                                    try { Thread.sleep(2000); } catch (Exception e) {}
                                    isInternalUpdate = false;
                                }).start();
                            }

                            // 只要勿擾狀態變更，必定切換睡眠模式
                            if (useBedtimeMode) {
                                boolean isEntering = (dndValue > 1);
                                String tips = isEntering ? "進入睡眠模式" : "退出睡眠模式";
                                
                                Log.d(TAG, "⚡ 勿擾觸發 -> 啟動獨立 Thread 後台自動化手勢沙盒: " + tips);
                                // 🌟 完美理解點 2：調用硬核 Thread 阻塞模式，防止手勢被吃
                                executeSafeGestureThread(isEntering, linkPowerSaveWithBedtime, tips);
                            }
                        }
                    }
                }

                // ⚙️ 業務 B：鬧鐘正向同步
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
                Log.e(TAG, "解析封包失敗", e);
            }
        }
    }

    /**
     * 🚀 終極硬核 Thread 自動化手勢守護沙盒
     * 使用單獨的後台線程進行精確的物理時間控制，確保手勢 100% 被系統識別。
     */
    private void executeSafeGestureThread(final boolean isEntering, final boolean linkPowerSave, final String tips) {
        new Thread(() -> {
            final DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
            if (serv == null) {
                Log.w(TAG, "⚠️ 輔助功能服務未連接，跳過自動化手勢。");
                return;
            }

            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = null;
            try {
                // 1. 強行點亮螢幕並持有最高優先級喚醒鎖
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dndsync:ThreadWLock");
                    wakeLock.acquire(6000L); // 預留足夠長的鎖時間
                }

                Log.d(TAG, "📺 Thread 守護：已發出屏幕點亮信號，等待硬件層徹底清醒...");
                // 🟢 【防吃核心優化】：利用 Thread.sleep 阻斷 800 毫秒，給手錶觸控驅動加載留足時間
                Thread.sleep(800);

                // 2. 執行下拉操作
                Log.d(TAG, "📺 Thread 守護：執行下拉控制中心");
                serv.swipeDown();
                // 下拉動畫伸展需要時間，阻斷等待 1000 毫秒
                Thread.sleep(1000);

                // 3. 執行精確點擊 40% 反轉睡眠狀態
                Log.d(TAG, "📺 Thread 守護：點擊睡眠模式圖標 (40% 坐標)");
                serv.clickIcon1_2();
                
                // 🌟【核心功能綁定】：在觸發睡眠模式點擊的同時，檢查省電聯動開關
                if (linkPowerSave) {
                    Log.d(TAG, "🔌 [省電聯動] 開關已開啟，同步將手錶系統底層 low_power 修改為: " + (isEntering ? 1 : 0));
                    setWearPowerSaveMode(isEntering);
                } else {
                    Log.d(TAG, "🔌 [省電聯動] 開關已關閉，不修改手錶省電狀態。");
                }
                
                // 點擊事件生效及反轉動畫需要時間，阻斷等待 1000 毫秒
                Thread.sleep(1000);

                // 4. 收起面板返回桌面
                Log.d(TAG, "📺 Thread 守護：手勢結束，返回收起控制面板");
                serv.goBack();

            } catch (InterruptedException e) {
                Log.e(TAG, "Thread 自動化手勢被中斷", e);
            } finally {
                // 安全釋放喚醒鎖
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                    Log.d(TAG, "📺 Thread 守護：釋放 WakeLock 喚醒鎖");
                }
            }
        }).start();
    }

    /**
     * 修改系統設置控制手表的低功耗省電狀態
     */
    private void setWearPowerSaveMode(boolean enable) {
        try {
            int target = enable ? 1 : 0;
            Settings.Global.putInt(getContentResolver(), "low_power", target);
            Log.d(TAG, "✅ [系統設置修改] low_power 已成功寫入為: " + target);
        } catch (Exception e) {
            Log.e(TAG, "❌ 修改系統 low_power 失敗", e);
        }
    }

    public static void sendDismissToPhone() {
        if (serviceContext == null) return;
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm");
                json.put("alarmAction", "dismiss");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(serviceContext).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(serviceContext).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {}
        }).start();
    }

    private void startLoopVibration() {
        try {
            if (globalVibrator == null) globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000};
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                }
            }
        } catch (Exception e) {}
    }

    public static void stopLoopVibration() {
        if (globalVibrator != null) globalVibrator.cancel();
    }
}
