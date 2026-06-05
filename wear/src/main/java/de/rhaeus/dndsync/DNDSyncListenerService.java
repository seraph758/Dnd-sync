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
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    
    private static Vibrator globalVibrator = null;
    
    // 🎯 核心控制：鬧鐘持續循環震動與響鈴的狀態鎖
    private static boolean isVibratingLoop = false;
    private static Thread alarmVibrationThread = null;

    @Override
    public void onCreate() {
        super.onCreate();
        if (globalVibrator == null) {
            globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private SharedPreferences getDndSyncPreferences() {
        return getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                // 過濾掉穿戴端自己發出的反向控制
                if (!"phone".equals(sender)) return;

                String type = json.optString("type", "");

                // ==================== 1. 處理勿擾模式同步 ====================
                if ("dnd".equals(type)) {
                    int dndState = json.getInt("dndValue");
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);
                    boolean isRealTimeSync = json.optBoolean("isRealTimeSync", false);

                    SharedPreferences prefs = getDndSyncPreferences();
                    boolean isDndSyncEnabled = prefs.getBoolean("dnd_sync_switch", true);

                    if (isDndSyncEnabled) {
                        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (mNotificationManager != null) {
                            int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                            
                            if (currentFilter != dndState) {
                                isInternalUpdate = true;
                                mNotificationManager.setInterruptionFilter(dndState);
                                Log.d(TAG, "手錶端成功應用勿擾狀態: " + dndState);
                                
                                if (wearPowerSave) {
                                    setLowPowerMode(dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                                }
                                
                                wakeUpWatchScreen();
                                triggerSleepModeClickThread(dndState);
                            }
                        }
                    }

                    if (wearVibrate && isRealTimeSync) {
                        triggerSingleVibration();
                    }
                }
                
                // ==================== 2. 🎯 處理手機鬧鐘同步（新增核心處理） ====================
                else if ("alarm".equals(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    Log.d(TAG, "⏰ 收到手機鬧鐘信號，行為: " + alarmAction);
                    
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        // 喚醒手錶螢幕，並啟動獨立線程進行無限循環震動
                        wakeUpWatchScreen();
                        startLoopVibration();
                        
                        // 🚀 核心聯動：自動拉起你寫好的全螢幕鬧鐘 Activity (WearAlarmActivity)
                        Intent alarmIntent = new Intent();
                        alarmIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearAlarmActivity");
                        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                           | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                           | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(alarmIntent);
                    } 
                    else if ("stopped".equalsIgnoreCase(alarmAction) || "snooze".equalsIgnoreCase(alarmAction)) {
                        // 手機端掛斷或小睡了，手錶端立刻停震並收工
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析同步封包失敗", e);
            }
        }
    }

    // 🎯 啟動背景非阻塞循環震動執行緒
    private synchronized void startLoopVibration() {
        if (isVibratingLoop) return; // 防止重複觸發導致多重震動鎖死
        isVibratingLoop = true;
        
        alarmVibrationThread = new Thread(() -> {
            try {
                Log.d(TAG, "📳 手錶鬧鐘循環震動執行緒啟動...");
                while (isVibratingLoop) {
                    if (globalVibrator != null && globalVibrator.hasVibrator()) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            // 採用 800ms 震動 + 400ms 間隔的強烈节奏
                            globalVibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            globalVibrator.vibrate(800);
                        }
                    }
                    // 執行緒睡眠 1.2 秒後進入下一次震動判定
                    Thread.sleep(1200);
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "執行緒收到插斷信號，正常退出震動");
            } catch (Exception e) {
                Log.e(TAG, "循環震動異常", e);
            }
        });
        alarmVibrationThread.start();
    }

    // 🎯 靜態公共方法：供本服務或者 WearAlarmActivity / DNDNotificationService 呼叫
    public static synchronized void stopLoopVibration() {
        try {
            Log.d(TAG, "🛑 收到全局停止持續震動控制訊號");
            isVibratingLoop = false;
            
            if (alarmVibrationThread != null) {
                alarmVibrationThread.interrupt();
                alarmVibrationThread = null;
            }
            if (globalVibrator != null) {
                globalVibrator.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "停止循環震動失敗", e);
        }
    }

    private void wakeUpWatchScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                    "WearSync:ForceWakeScreen"
                );
                wakeLock.acquire(3000); // 強行亮屏3秒
                Log.d(TAG, "⚡ 已發送硬體級喚醒指令點亮手錶屏幕");
            }
        } catch (Exception e) {
            Log.e(TAG, "喚醒屏幕失敗", e);
        }
    }

    private void triggerSleepModeClickThread(int dndState) {
        new Thread(() -> {
            try {
                Thread.sleep(500); 
                boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                Settings.Global.putInt(getContentResolver(), "bedtime_mode_is_active", isDndActive ? 1 : 0);
                
                Intent modeIntent = new Intent("com.google.android.clockwork.actions.BEDTIME_MODE_CHANGED");
                sendBroadcast(modeIntent);
                Log.d(TAG, "✨ 睡眠模式聯動狀態已刷新。");
            } catch (Exception e) {
                Log.e(TAG, "自動化聯動流中斷", e);
            }
        }).start();
    }

    private void setLowPowerMode(boolean enable) {
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", enable ? 1 : 0);
            sendBroadcast(new Intent("android.os.action.POWER_SAVE_MODE_CHANGED"));
        } catch (Exception e) {
            Log.e(TAG, "改變省電狀態失敗", e);
        }
    }

    private void triggerSingleVibration() {
        try {
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    globalVibrator.vibrate(200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "震動失敗", e);
        }
    }
}
