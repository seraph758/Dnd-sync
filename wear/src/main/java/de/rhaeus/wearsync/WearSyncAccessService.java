package de.rhaeus.wearsync;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class WearSyncAccessService extends AccessibilityService {

    private static WearSyncAccessService instance;

    public static WearSyncAccessService getSharedInstance() {
        return instance;
    }
    @Override
    protected void onServiceConnected() {
        instance = this;
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
        boolean result = dispatchGesture(createClick(x, y), null, null);
    }

    public void clickIcon1_2() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int midX = displayMetrics.widthPixels / 2;

        path.moveTo(midX, (int)(height * .4));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }

    public void swipeDown() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        final int height = displayMetrics.heightPixels;
        final int mid = (int)(height * .5);
        final int midX = displayMetrics.widthPixels / 2;

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

    // 🎯 睡眠模式自动化入口（传入 true 开启，false 关闭）
    public void triggerBedtimeMacro(boolean enable) {
        new Thread(() -> {
            try {
                swipeDown(); // 下拉快捷栏
                Thread.sleep(1000);
                clickIcon1_2(); 
                Thread.sleep(1000);
                goBack(); // 收起
                Log.d("AccessService", "睡眠自动化宏执行成功 -> " + enable);
            } catch (Exception e) {
                Log.e("AccessService", "执行睡眠自动化异常", e);
            }
        }).start();
    }

    // 🎯 省电模式自动化入口（传入 true 开启，false 关闭）
    public void triggerPowerSavingMacro(boolean enable) {
        new Thread(() -> {
            try {
                swipeDown();
                Thread.sleep(1000);
                clickIcon1_2(); 
                Thread.sleep(1000);
                goBack();
                Log.d("AccessService", "省电自动化宏执行成功 -> " + enable);
            } catch (Exception e) {
                Log.e("AccessService", "执行省电自动化异常", e);
            }
        }).start();
    }
}
