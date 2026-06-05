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

    // 提供给 Activity 调用的静态停止震动方法
    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel();
                Log.d(TAG, "🛑 收到停止持续震动指令，已成功拦截关断");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止震动发生异常", e);
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        // 确保只处理匹配我们万能互联协议路径的数据
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                Log.d(TAG, "📥 手表端收到高优先级互联数据包: " + jsonStr);

                // 1. 尝试使用全新的标准 JSON 数据包模式进行高阶沙盒解析
                try {
                    JSONObject json = new JSONObject(jsonStr);
                    String sender = json.optString("sender", "");
                    if ("wear".equalsIgnoreCase(sender)) return; // 过滤自身回环

                    String type = json.optString("type", "");

                    // 🎯 A. 闹钟防漏沙盒逻辑响应区
                    if ("alarm".equalsIgnoreCase(type)) {
                        String alarmAction = json.optString("alarmAction", "");
                        Log.d(TAG, "⏰ 捕获到手机端传来的闹钟硬联动信号: " + alarmAction);
                        
                        if ("LAUNCH_WEAR_ALARM_ACTIVITY".equalsIgnoreCase(alarmAction)) {
                            // 核心硬拉起：直接唤醒手表的 WearAlarmActivity
                            Intent alarmIntent = new Intent();
                            alarmIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearAlarmActivity");
                            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            alarmIntent.putExtra("dismissActionConfig", json.optString("dismissActionConfig", "停止和延后"));
                            startActivity(alarmIntent);
                            Log.d(TAG, "🚀 成功硬拉起手表端 WearAlarmActivity.java");
                        } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(alarmAction)) {
                            // 手机端闹钟挂断或被划掉，发送本地广播通知 WearAlarmActivity 自毁销毁
                            Intent stopBroadcast = new Intent("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI");
                            sendBroadcast(stopBroadcast);
                            stopLoopVibration();
                        }
                        return; // 消费完毕，直接截断返回
                    }

                    // 🎯 B. 相机取景投射沙盒逻辑响应区
                    if ("camera_control".equalsIgnoreCase(type)) {
                        String action = json.optString("action", "");
                        Log.d(TAG, "📸 捕获到手机端传来的相机投射信号: " + action);
                        
                        if ("LAUNCH_WEAR_CAMERA_ACTIVITY".equalsIgnoreCase(action)) {
                            // 核心硬拉起：直接唤醒手表的 WearCameraActivity
                            Intent cameraIntent = new Intent();
                            cameraIntent.setClassName(getPackageName(), "de.rhaeus.dndsync.WearCameraActivity");
                            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(cameraIntent);
                            Log.d(TAG, "🚀 成功硬拉起手表端 WearCameraActivity.java");
                        }
                        return; // 消费完毕，直接截断返回
                    }

                    // 🎯 C. 勿扰与核心模式深度联动响应区（睡眠模式/省电模式都在这里处理）
                    if ("dnd".equalsIgnoreCase(type)) {
                        int dndState = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                        boolean dndMaster = json.optBoolean("dndSyncMaster", true);
                        boolean wearSleepLink = json.optBoolean("wearSleepModeLink", true);
                        boolean wearPowerLink = json.optBoolean("wearPowerSave", false);

                        if (!dndMaster) {
                            Log.d(TAG, "⚠️ 勿扰总开关未开启，放弃本次勿扰相关联动");
                            return;
                        }

                        // 执行手表端勿扰设置
                        setWearDndState(dndState);

                        // ⚡ 完美解密并强制同步【智能睡眠模式】
                        if (wearSleepLink) {
                            boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                            setWearBedtimeMode(isDndActive);
                        }

                        // ⚡ 完美解密并强制同步【系统省电模式】
                        if (wearPowerLink) {
                            boolean isDndActive = (dndState != NotificationManager.INTERRUPTION_FILTER_ALL);
                            setLowPowerMode(isDndActive);
                        }
                        return;
                    }

                } catch (Exception jsonEx) {
                    // 2. 备用降级逻辑：如果传过来的是旧版框架的纯文本/纯数字，走降级兼容处理
                    Log.d(TAG, "ℹ️ 降级为旧版本数据流解析...");
                    String valStr = jsonStr.trim();
                    int dndVal = Integer.parseInt(valStr);
                    setWearDndState(dndVal);
                }

            } catch (Exception e) {
                Log.e(TAG, "穿戴端核心消息消费流彻底崩溃：", e);
            }
        }
    }

    // 内部抽象出的高阶原子操作：更改手表勿扰状态
    private void setWearDndState(int dndState) {
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                isInternalUpdate = true;
                notificationManager.setInterruptionFilter(dndState);
                Log.d(TAG, "🎯 手表系统原生勿扰状态已成功强制解算同步为: " + dndState);
            }
        } catch (Exception e) {
            Log.e(TAG, "手表写入勿扰通道失败", e);
        }
    }

    // 内部抽象出的高阶原子操作：强制修改手表系统注册表实现【睡眠模式同步】
    private void setWearBedtimeMode(boolean active) {
        new Thread(() -> {
            try {
                // 写入 WearOS 核心骨架注册表中的智能睡眠模式键值
                Settings.Global.putInt(getContentResolver(), "bedtime_mode_is_active", active ? 1 : 0);
                
                // 向系统内核群发床头/睡眠状态变更的系统级广播，强迫 WearOS 状态栏和系统重绘
                Intent modeIntent = new Intent("com.google.android.clockwork.actions.BEDTIME_MODE_CHANGED");
                sendBroadcast(modeIntent);
                Log.d(TAG, "🌙 睡眠模式(Bedtime Mode)深度联动同步成功 -> " + (active ? "激活" : "关闭"));
            } catch (Exception e) {
                Log.e(TAG, "深度联动睡眠模式失败", e);
            }
        }).start();
    }

    // 内部抽象出的高阶原子操作：强制修改手表系统注册表实现【省电模式同步】
    private void setLowPowerMode(boolean enable) {
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", enable ? 1 : 0);
            sendBroadcast(new Intent("android.os.action.POWER_SAVE_MODE_CHANGED"));
            Log.d(TAG, "🔋 系统省电模式深度状态联动同步成功 -> " + (enable ? "开启" : "关闭"));
        } catch (Exception e) {
            Log.e(TAG, "改变手表省电状态失败", e);
        }
    }
}
