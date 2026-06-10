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

// 显式导入无障碍辅助服务，确保符号正常识别
import de.rhaeus.wearsync.WearAccessibilityService;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 手势内部宏防并发锁
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

            // 🎯【相机远程连线唤醒控制区】
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "⌚ 手表后台收到手机端相机控制信令 Action: " + action);
                if ("START_CAMERA".equals(action)) {
                    // 🚀 核心破局：收到手机唤醒指令，瞬间拔起手表端相机取景界面！
                    Intent startIntent = new Intent(this, WearCameraActivity.class);
                    // 🟢【完美修正】：补全了前面漏掉的 Intent. 前缀，彻底根治编译 FAILED 问题
                    startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                       | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                                       | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(startIntent);
                    Log.d(TAG, "🚀 已成功由后台 ListenerService 强行弹出手表端 WearCameraActivity 画面");
                }
                return; // 处理完毕直接拦截返回，不往下走勿扰同步逻辑
            }

            // 1️⃣ 勿扰/就寝同步区
            if ("dnd".equalsIgnoreCase(type)) {
                String dndState = json.optString("state", "off");
                Log.d(TAG, "DND state from phone: " + dndState);
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    if ("on".equals(dndState)) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
                    } else {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                    }
                }
            } else if ("bedtime".equalsIgnoreCase(type) || "power_saving".equalsIgnoreCase(type)) {
                // 就寝模式/省电模式 触发物理手势宏处理
                String state = json.optString("state", "off");
                Log.d(TAG, "收到状态变更信令 -> type: " + type + ", state: " + state);

                if ("on".equals(state)) {
                    // 仅在开启时触发物理宏同步
                    triggerHardwareGestureMacroWithLock();
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "解析手机信令失败", e);
        }
    }

    private synchronized void triggerHardwareGestureMacroWithLock() {
        if (isGestureMacroRunning) {
            Log.w(TAG, "⚠️ 检测到已有手势宏正在运行，阻断本次触发，防止并发踩踏！");
            return;
        }

        isGestureMacroRunning = true;
        Log.d(TAG, "🔒 成功抢占手势宏并发锁，启动物理校准链条...");

        new Thread(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                // 震动反馈感知
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                }

                // 检查辅助功能是否就绪
                WearAccessibilityService serv = WearAccessibilityService.getInstance();
                if (serv == null) {
                    Log.e(TAG, "❌ 辅助功能未开启，手势宏无法执行！");
                    return;
                }

                // 1. 强制唤醒屏幕，保证物理点击有效
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                    wakeLock.acquire(8000L); // 锁定 8 秒完成整套动作
                }

                // 2. 核心时序缓冲：留出 2000ms 让屏幕硬件完全亮起，防止屏幕没亮完导致下滑无效
                Thread.sleep(2000);

                // 3. 调用旧代码纯净下滑
                serv.swipeDown();

                // 4. 时序缓冲：留出 1000ms 给下拉菜单动画展开，防止面板还没拉下来就去盲点
                Thread.sleep(1000);

                // 5. 点击第一排中间图标
                serv.clickIcon1_2();

                // 6. 时序缓冲：留出 1000ms 给系统响应状态变更
                Thread.sleep(800);

                // 7. 收起快捷面板
                serv.goBack();
                Log.d(TAG, "🏁 [手势宏] 物理控制校准链条圆满结束");

            } catch (InterruptedException e) {
                Log.e(TAG, "手势宏线程中断", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                isGestureMacroRunning = false;
                Log.d(TAG, "🔓 手势宏运行完毕，并发锁已安全释放。");
            }
        }).start();
    }
}
