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

    /**
     * 建议：既然你反馈源文件的手势下拉有效，这里直接优先调用手势。
     * 因为系统指令 (GLOBAL_ACTION_QUICK_SETTINGS) 在 Wear OS 5 上极不稳定。
     */
    public void openQuickSettings() {
        Log.d(TAG, "尝试下拉菜单...");
        // 优先执行已被证明有效的模拟手势
        swipeDown();
        
        // 如果你坚持想用原生指令，可以把下面两行取消注释，但通常手势更可靠
        // boolean success = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        // if (!success) swipeDown();
    }

    public void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void clickIcon1_2() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        // 保持你确认过的 40% 高度坐标
        click(width * 0.5f, height * 0.4f);
    }

    /**
     * 核心修复：修复了 width 变量错误，并恢复了你源文件中证明有效的 100/50 节奏
     */
    public void swipeDown() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int width = displayMetrics.widthPixels; // 修复：定义 width
        final int midX = width / 2;

        // 保持从 0 开始。既然源文件能勾住菜单，就不要改到 20。
        path.moveTo(midX, 0); 
        path.lineTo(midX, height * 0.6f); // 滑动长度稍微增加到 60% 确保拉到底

        // 100ms 延迟确保系统准备好接收触摸，50ms 快速甩动
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    public void click(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 点击延迟设为 0 立即执行，持续 50ms 模拟正常点击
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(builder.build(), null, null);
    }
}
