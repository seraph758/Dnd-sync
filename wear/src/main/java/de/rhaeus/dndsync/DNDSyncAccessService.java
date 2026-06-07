package de.rhaeus.dndsync;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class DNDSyncAccessService extends AccessibilityService {

    private static DNDSyncAccessService instance;

    public static DNDSyncAccessService getSharedInstance() {
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
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {

    }

    @Override
    public void onInterrupt() {

    }

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
        final int top = (int)(height * .25);
        final int mid = (int)(height * .5);
        final int bottom = (int)(height * .75);
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
        final int top = (int)(height * .25);
        final int mid = (int)(height * .5);
        final int bottom = (int)(height * .75);
        final int midX = displayMetrics.widthPixels / 2;

        path.moveTo(midX, 0);
        path.lineTo(midX, mid);
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50));
        dispatchGesture(gestureBuilder.build(), null, null);
    }
        // 🎯 補上手錶監聽服務呼叫的 Bedtime 睡眠模式巨集入口
    public void triggerBedtimeMacro() {
        new Thread(() -> {
            try {
                swipeDown();      // 下拉快捷欄
                Thread.sleep(1000);
                clickIcon1_2();   // 自動定位並模擬點擊睡眠圖標
                Thread.sleep(1000);
                goBack();         // 收起狀態欄
            } catch (Exception e) {
                android.util.Log.e("AccessService", "執行睡眠巨集失敗", e);
            }
        }).start();
    }

    // 🎯 補上手錶監聽服務呼叫的 PowerSaving 省電模式巨集入口
    public void triggerPowerSavingMacro() {
        new Thread(() -> {
            try {
                swipeDown();      // 下拉快捷欄
                Thread.sleep(1000);
                // 這裡模擬點擊省電模式圖標，先使用預設的圖標1_2點擊（您可以根據手錶排版微調）
                clickIcon1_2();   
                Thread.sleep(1000);
                goBack();         // 收起狀態欄
            } catch (Exception e) {
                android.util.Log.e("AccessService", "執行省電巨集失敗", e);
            }
        }).start();
    }

    // (x, y) in screen coordinates
    private static GestureDescription createClick(float x, float y) {
        // for a single tap a duration of 1 ms is enough
        final int DURATION = 1;

        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        GestureDescription.StrokeDescription clickStroke =
                new GestureDescription.StrokeDescription(clickPath, 0, DURATION);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

}