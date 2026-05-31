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
        Log.d(TAG, "辅助功能服务已连接");
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    public void openQuickSettings() {
        Log.d(TAG, "尝试下拉菜单...");
        swipeDown();
    }

    public void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * 保持你确认过的 40% 高度坐标 (支持自定延迟)
     * @param startTime 延迟发射手势的毫秒数
     */
    public void clickIcon1_2(long startTime) {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        // 宽度 50%, 高度 40%
        click(width * 0.5f, height * 0.4f, startTime);
    }

    /**
     * 新增：宽度 50%，高度 80% 的点击方法
     * @param startTime 延迟发射手势的毫秒数
     */
    public void clickIconAt80Percent(long startTime) {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        // 宽度 50%, 高度 80%
        click(width * 0.5f, height * 0.8f, startTime);
    }

    public void swipeDown() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int width = displayMetrics.widthPixels;
        final int midX = width / 2;

        path.moveTo(midX, 0); 
        path.lineTo(midX, height * 0.6f);

        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    /**
     * 核心重构：支持控制发射时机 (startTime) 的底座方法
     */
    public void click(float x, float y, long startTime) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        
        // startTime 控制这个手势在队列里第几毫秒被按下，持续 50ms 模拟正常点击
        builder.addStroke(new GestureDescription.StrokeDescription(path, startTime, 50));
        dispatchGesture(builder.build(), null, null);
    }

    // 🎯 修复：将其重定向到新方法，传入 0ms 立即执行，防止老方法兼容报错
    public void clickIcon1_2() {
        clickIcon1_2(0);
    }
}
