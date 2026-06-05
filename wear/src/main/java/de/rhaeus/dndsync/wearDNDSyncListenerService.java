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
                if (!"phone".equals(sender)) return;

                String type = json.optString("type", "");

                if ("dnd".equals(type)) {
                    int dndState = json.getInt("dndValue");
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);
                    // 核心修复 2：只有当手机系统状态真正改变触发的同步，才进行震动
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
                                
                                // 联动修改：只有勾选了省电连动，才去改变手表的省电模式
                                if (wearPowerSave) {
                                    setLowPowerMode(dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                                }
                                
                                // 核心修复 1：点亮屏幕，并开始下拉执行联动睡眠模式点击
                                wakeUpWatchScreen();
                                triggerSleepModeClickThread(dndState);
                            }
                        }
                    }

                    if (wearVibrate && isRealTimeSync) {
                        triggerSingleVibration();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "解析同步封包失败", e);
            }
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
                wakeLock.acquire(3000); // 强行硬件亮屏3秒，为自动化手势腾出物理时间
                Log.d(TAG, "⚡ 勿扰联动：已发送硬件级唤醒指令点亮手表屏幕");
            }
        } catch (Exception e) {
            Log.e(TAG, "唤醒屏幕失败", e);
        }
    }
    private void triggerSleepModeClickThread(int dndState) {
        new Thread(() -> {
            try {
                // 等待屏幕完全点亮和窗口焦点就绪
                Thread.sleep(500); 
                Log.d(TAG, "🚀 开始执行下拉自动化点击流。当前同步状态: " + dndState);
                
                // 自动联动睡眠模式：当勿扰开启时(dndState != 1)，自动将手表的系统内置床头/睡眠模式激活
                boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                Settings.Global.putInt(getContentResolver(), "bedtime_mode_is_active", isDndActive ? 1 : 0);
                
                // 发送全局广播通知手表系统重绘状态栏
                Intent modeIntent = new Intent("com.google.android.clockwork.actions.BEDTIME_MODE_CHANGED");
                sendBroadcast(modeIntent);
                Log.d(TAG, "✨ 睡眠模式联动状态已刷新，已同步写入系统注册表。");
            } catch (Exception e) {
                Log.e(TAG, "自动化联动流中断", e);
            }
        }).start();
    }

    private void setLowPowerMode(boolean enable) {
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", enable ? 1 : 0);
            sendBroadcast(new Intent("android.os.action.POWER_SAVE_MODE_CHANGED"));
        } catch (Exception e) {
            Log.e(TAG, "改变省电状态失败", e);
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
            Log.e(TAG, "震动失败", e);
        }
    }
}
