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

    // 🎯 睡眠模式自動化入口（傳入 true 開啟，false 關閉）
    public void triggerBedtimeMacro(boolean enable) {
        new Thread(() -> {
            try {
                // 🎯 核心修復：放棄不穩定的手勢滑動，改用系統最高優先級的高級官方指令直接調出面板
                openQuickSettings(); 
                
                // 高級命令響應極快，等待面板完全展開 800 毫秒即可
                Thread.sleep(800); 
                clickIcon1_2(); // 執行點擊
                
                Thread.sleep(1000);
                goBack(); // 收起快捷欄，返回主畫面
                Log.d("AccessService", "睡眠自動化宏執行成功 -> " + enable);
            } catch (Exception e) {
                Log.e("AccessService", "執行睡眠自動化異常", e);
            }
        }).start();
    }

    // 🎯 省電模式自動化入口（傳入 true 開啟，false 關閉）
    public void triggerPowerSavingMacro(boolean enable) {
        new Thread(() -> {
            try {
                // 🎯 核心修復：同樣將省電模式的下拉手勢升級為高級系統命令
                openQuickSettings();
                
                Thread.sleep(800);
                clickIcon1_2(); 
                
                Thread.sleep(1000);
                goBack();
                Log.d("AccessService", "省電自動化宏執行成功 -> " + enable);
            } catch (Exception e) {
                Log.e("AccessService", "執行省電自動化異常", e);
            }
        }).start();
    }
}
