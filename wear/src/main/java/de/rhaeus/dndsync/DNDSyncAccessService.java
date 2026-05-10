package de.rhaeus.dndsync;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class DNDSyncAccessService extends AccessibilityService {
    private static final String TAG = "DNDSyncAccess";
    private static DNDSyncAccessService instance;

    public static DNDSyncAccessService getSharedInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        Log.d(TAG, "服务已连接");
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    // 优化：尝试原生下拉，失败则自动切换到手势下拉
    public void openQuickSettings() {
        boolean success = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        if (!success) {
            Log.d(TAG, "原生下拉失败，尝试手动模拟滑动");
            swipeDown();
        }
    }

    public void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    // 优化：三星手表的下拉图标位置通常在屏幕上半部分
    // 假设“就寝模式”在第一页
    public void clickIcon1_2() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        // Watch 7 的图标阵列：
        // 这里的坐标 (width * 0.5, height * 0.4) 对应中间偏上的图标
        click(width * 0.5f, height * 0.4f);
    }

    // 核心修复：调整起点坐标 y=20 避开系统死区
    public void swipeDown() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        Path path = new Path();
        // 从顶部往下一点开始（y=20），滑到屏幕 70% 的位置
        path.moveTo(width / 2f, 20f); 
        path.lineTo(width / 2f, height * 0.7f);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 修正：startTime 设为 0，立即执行；duration 设为 200ms 模拟正常手势速度
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 200));
        dispatchGesture(builder.build(), null, null);
    }

    public void click(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 点击持续时间建议 50ms 左右最稳定
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(builder.build(), null, null);
    }
}
