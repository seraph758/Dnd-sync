package de.rhaeus.wearsync;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class WearSyncAccessService extends AccessibilityService {

    private static final String TAG = "WearSyncAccessService";
    private static WearSyncAccessService instance;

    public static WearSyncAccessService getSharedInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.d(TAG, "✅ 三星物理穿透无障碍服务已成功连接并绑定句柄 (全新包名: de.rhaeus.wearsync)");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {}

    @Override
    public void onInterrupt() {}

    // === [AI_SECURITY_FIREWALL: MODERN_SAMSUNG_PHYSICAL_DRIVE_CORE] ===
    // 底层手势驱动函数：完全对齐 100ms 延迟、50ms 猛烈下滑与 50ms 坐标黄金重击按压

    public void openQuickSettings() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    public void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void openNotification() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void click(float x, float y) {
        dispatchGesture(createClick(x, y), null, null);
    }

    /**
     * 🎯 点击逻辑：严格模拟点击屏幕横向 50% 正中央，纵向 40% 的位置
     * 笔画持续 50 毫秒以形成极其稳定的物理触控模拟
     */
    public void clickIcon1_2() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int midX = displayMetrics.widthPixels / 2;
        final int targetY = (int)(height * .4);

        Log.d(TAG, "🤖 [手势指令] 执行坐标重击 -> X: " + midX + ", Y: " + targetY + " (屏幕比例：宽50%, 高40%)");
        path.moveTo(midX, targetY);
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    /**
     * 🧱 下拉逻辑：延迟 100ms，在 50ms 内从顶部 (midX, 0) 快速下滑到屏幕 50% 中部 (midX, mid)
     * 纯手势驱动，强行撕开三星 One UI Watch 面板
     */
    public void swipeDown() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int mid = (int)(height * .5);
        final int midX = displayMetrics.widthPixels / 2;

        Log.d(TAG, "🤖 [手势指令] 执行物理猛烈下拉 -> 从 Y:0 到 Y:" + mid);
        path.moveTo(midX, 0);
        path.lineTo(midX, mid);
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    private static GestureDescription createClick(float x, float y) {
        final int DURATION = 1;
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        GestureDescription.StrokeDescription clickStroke =
                new GestureDescription.StrokeDescription(clickPath, 0, DURATION);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }
    // === [AI_SECURITY_FIREWALL_END: MODERN_SAMSUNG_PHYSICAL_DRIVE_CORE] ===


    // === [AI_SECURITY_FIREWALL: MODERN_REFERENCE_TOGGLE_MACRO_SCHEDULER] ===
    // 现代自动化业务总线：完美参考并移植 WakeLock 亮屏机制与标准串行切换时序

    /**
     * 🛌 睡眠模式自动化入口（对应手机端下发的分数 1）
     */
    public void triggerBedtimeMacro(boolean enable) {
        new Thread(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                Log.d(TAG, "🚀 [睡眠宏] 触发序列激活，当前架构: wearsync。期望状态: " + enable);

                // 1. 物理强制唤醒锁：强行点亮手表屏幕，击穿黑屏休眠下的系统拦截
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wearsync:MyWakeLock");
                wakeLock.acquire(2 * 60 * 1000L /* 保持唤醒 2 分钟 */);

                // 2. 主线程弹出状态提示
                Handler mHandler = new Handler(getMainLooper());
                mHandler.post(() -> Toast.makeText(getApplicationContext(), "同步驱动：睡眠模式切换中...", Toast.LENGTH_SHORT).show());

                // 3. 严格遵循参考时序：等待 1 秒确保屏幕完全点亮并准备就绪
                Thread.sleep(1000);

                // 4. 发动物理下拉快捷栏
                swipeDown();

                // 5. 严格遵循参考时序：等待 1 秒给面板动画舒展时间
                Thread.sleep(1000);

                // 6. 轰击目标黄金位置 (横向 50%、纵向 40%)
                clickIcon1_2();

                // 7. 严格遵循参考时序：等待 1 秒确保开关状态被三星系统吞入并生效
                Thread.sleep(1000);

                // 8. 调用系统返回键收起下拉状态栏
                goBack();

                Log.d(TAG, "🏁 [睡眠宏] 穿透控制链路串行结束。");
            } catch (Exception e) {
                Log.e(TAG, "❌ [睡眠宏] 物理触控执行异常", e);
            } finally {
                // 9. 安全释放唤醒锁，恢复常规省电
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                    Log.d(TAG, "🔒 [睡眠宏] 唤醒锁已安全释放");
                }
            }
        }).start();
    }

    /**
     * 🔋 省电模式自动化入口（对应手机端下发的分数 2）
     */
    public void triggerPowerSavingMacro(boolean enable) {
        new Thread(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                Log.d(TAG, "🚀 [省电宏] 触发序列激活，当前架构: wearsync。期望状态: " + enable);

                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wearsync:MyWakeLock");
                wakeLock.acquire(2 * 60 * 1000L);

                Thread.sleep(1000);
                swipeDown();
                
                Thread.sleep(1000);
                clickIcon1_2();
                
                Thread.sleep(1000);
                goBack();

                Log.d(TAG, "🏁 [省电宏] 穿透控制链路串行结束。");
            } catch (Exception e) {
                Log.e(TAG, "❌ [省电宏] 物理触控执行异常", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }).start();
    }
    // === [AI_SECURITY_FIREWALL_END: MODERN_REFERENCE_TOGGLE_MACRO_SCHEDULER] ===
}
