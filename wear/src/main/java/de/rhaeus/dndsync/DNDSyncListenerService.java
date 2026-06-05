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

    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel();
                Log.d(TAG, "🛑 收到停止持续震动指令");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止震动异常", e);
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                Log.d(TAG, "📥 手表端收到高优先级互联数据包: " + jsonStr);

                try {
                    JSONObject json = new JSONObject(jsonStr);
                    String sender = json.optString("sender", "");
                    if ("wear".equalsIgnoreCase(sender)) return;

                    String type = json.optString("type", "");

                    // 🎯 A. 闹钟处理
                    if ("alarm".equalsIgnoreCase(type)) {
                        String alarmAction = json.optString("alarmAction", "");
                        if ("LAUNCH_WEAR_ALARM_ACTIVITY".equalsIgnoreCase(alarmAction)) {
                            Intent alarmIntent = new Intent();
                            alarmIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearAlarmActivity");
                            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(alarmIntent);
                        } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(alarmAction)) {
                            Intent stopBroadcast = new Intent("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI");
                            sendBroadcast(stopBroadcast);
                            stopLoopVibration();
                        }
                        return;
                    }

                    // 🎯 B. 相机处理
                    if ("camera_control".equalsIgnoreCase(type)) {
                        String action = json.optString("action", "");
                        if ("LAUNCH_WEAR_CAMERA_ACTIVITY".equalsIgnoreCase(action)) {
                            Intent cameraIntent = new Intent();
                            cameraIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearCameraActivity");
                            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(cameraIntent);
                        }
                        return;
                    }

                    // 🎯 C. 勿扰和强依赖树（包含物理触感震动与智能下拉触控）
                    if ("dnd".equalsIgnoreCase(type)) {
                        int dndState = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                        boolean dndMaster = json.optBoolean("dndSyncMaster", true);
                        boolean wearSleepLink = json.optBoolean("wearSleepModeLink", true);
                        boolean wearPowerLink = json.optBoolean("wearPowerSave", false);
                        boolean vibrateTipsEnable = json.optBoolean("vibrateTipsEnable", true);

                        if (!dndMaster) return;

                        // 执行手表同步勿扰
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) {
                            isInternalUpdate = true;
                            nm.setInterruptionFilter(dndState);
                        }

                        // 🎯 物理瞬时触觉反馈触发：如果开启了震动开关，震动 50 毫秒
                        if (vibrateTipsEnable && globalVibrator != null) {
                            globalVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                        }

                        // 🎯 睡眠模式同步：如果需要联动，则执行核心无障碍宏点击操作
                        if (wearSleepLink) {
                            boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                            // 由于直接写注册表被安全拦截，必须利用无障碍模拟行为切换
                            executeBedtimeToggleUiMacro(isDndActive);
                        }

                        if (wearPowerLink) {
                            boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                            try {
                                Settings.Global.putInt(getContentResolver(), "low_power", isDndActive ? 1 : 0);
                                sendBroadcast(new Intent("android.os.action.POWER_SAVE_MODE_CHANGED"));
                            } catch (Exception e) {}
                        }
                        return;
                    }

                } catch (Exception jsonEx) {
                    // 降级兼容
                    String valStr = jsonStr.trim();
                    int dndVal = Integer.parseInt(valStr);
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) { nm.setInterruptionFilter(dndVal); }
                }

            } catch (Exception e) {
                Log.e(TAG, "穿戴消息处理崩溃：", e);
            }
        }
    }

    // 🎯 核心移植：执行屏幕唤醒并使用无障碍自动切换睡眠模式
    private void executeBedtimeToggleUiMacro(boolean targetActive) {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "无障碍服务未连接，放弃自动切换");
            return;
        }

        new Thread(() -> {
            try {
                // 点亮屏幕
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:WakeLock");
                wakeLock.acquire(10 * 1000L);

                Thread.sleep(1000);
                serv.swipeDown();      // 下拉快捷栏
                Thread.sleep(1000);
                serv.clickIcon1_2();    // 自动定位并模拟点击睡眠图标
                Thread.sleep(1000);
                serv.goBack();          // 收起状态栏
                
                if (wakeLock.isHeld()) { wakeLock.release(); }
                Log.d(TAG, "✨ 已通过旧版无障碍架构成功完成睡眠模式翻转动作");
            } catch (Exception e) {
                Log.e(TAG, "无障碍宏流程异常中断", e);
            }
        }).start();
    }
}
