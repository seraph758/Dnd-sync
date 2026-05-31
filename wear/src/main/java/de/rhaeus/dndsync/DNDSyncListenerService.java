package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;


import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService
        implements DataClient.OnDataChangedListener {

    private static final String TAG = "DNDSyncListenerService";

    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    private static final String DATA_PATH = "/dnd_state";

    // 防止短时间循环触发
    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 5000;

    // DataClient 数据最大有效时间
    private static final long DATA_EXPIRE_MS = 15000;

    public static boolean isInternalUpdate = false;

    private static final Handler handler =
            new Handler(android.os.Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();

        // 动态注册 capability
        Wearable.getCapabilityClient(this)
                .addLocalCapability("dnd_sync")
                .addOnSuccessListener(unused ->
                        Log.d(TAG, "动态注册 capability 成功"))
                .addOnFailureListener(e ->
                        Log.e(TAG, "动态注册 capability 失败", e));

        // 注册 DataClient listener
        Wearable.getDataClient(this)
                .addListener(this);

        Log.d(TAG, "DataClient listener 已注册");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Wearable.getCapabilityClient(this)
                .removeLocalCapability("dnd_sync");

        Wearable.getDataClient(this)
                .removeListener(this);

        Log.d(TAG, "listener 已移除");
    }

    // =========================================
    // MessageClient 实时同步
    // =========================================
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {

    if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "onMessageReceived: " + messageEvent);
    }

    // 🎯 新增拦截：收到手机端的全局设置同步包，先把开关状态存在本地供点击时读取
    if ("/settings-sync".equalsIgnoreCase(messageEvent.getPath())) {
        byte[] data = messageEvent.getData();
        if (data != null && data.length >= 3) {
            boolean powerSave = data[1] == 1;
            boolean wearPowerSave = data[2] == 1;
            // 只要这两个任意一个指示需要开启手錶省电，我们就认定省电开关在打开状态
            boolean isPowerSaveEnabledOnPhone = powerSave && wearPowerSave;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("phone_wear_power_save_state", isPowerSaveEnabledOnPhone).apply();
            Log.d(TAG, "已同步并缓存手机端省电开关状态: " + isPowerSaveEnabledOnPhone);
        }
        return; // 处理完毕，退出
    }

    // 以下是你原有的 /wear-dnd-sync 拦截逻辑，保持不变
    if (!messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
        super.onMessageReceived(messageEvent);
        return;
    }

    long currentTime = System.currentTimeMillis();
    if (currentTime - lastExecutionTime < COOLDOWN_MS) {
        Log.d(TAG, "還在冷卻期，忽略本次信號");
        return;
    }
    lastExecutionTime = currentTime;

    byte[] data = messageEvent.getData();
    if (data == null || data.length == 0) {
        Log.d(TAG, "MessageClient 数据为空");
        return;
    }

    int dndState = data[0];
    Log.d(TAG, "收到 MessageClient DND: " + dndState);
    applyDndState(dndState);
}
    // =========================================
    // DataClient 兜底同步
    // =========================================
    @Override
    public void onDataChanged(@NonNull DataEventBuffer buffer) {

        for (DataEvent event : buffer) {

            if (event.getType() != DataEvent.TYPE_CHANGED)
                continue;

            String path =
                    event.getDataItem().getUri().getPath();

            if (!DATA_PATH.equals(path))
                continue;

            DataMapItem mapItem =
                    DataMapItem.fromDataItem(event.getDataItem());

            int dndState =
                    mapItem.getDataMap().getInt("dnd");

            long time =
                    mapItem.getDataMap().getLong("time");

            long age =
                    System.currentTimeMillis() - time;

            // 超时数据直接丢弃
            if (age > DATA_EXPIRE_MS) {
                Log.d(TAG,
                        "丢弃过期 DataClient 数据: "
                                + age + "ms");
                continue;
            }

            Log.d(TAG,
                    "收到 DataClient DND: "
                            + dndState);

            applyDndState(dndState);
        }
    }

    // =========================================
    // 统一 DND 应用逻辑
    // =========================================
    private void applyDndState(int dndState) {

        NotificationManager mNotificationManager =
                (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);

        if (mNotificationManager == null) {
            Log.d(TAG, "NotificationManager 为 null");
            return;
        }

        if (!mNotificationManager
                .isNotificationPolicyAccessGranted()) {

            Log.d(TAG, "缺少勿扰模式访问权限");
            return;
        }

        int currentFilter =
                mNotificationManager.getCurrentInterruptionFilter();

        if (currentFilter == dndState) {
            Log.d(TAG, "DND 状态一致，无需同步");
            return;
        }

        // 防止回传死循环
        isInternalUpdate = true;

        handler.postDelayed(() -> {
            isInternalUpdate = false;
            Log.d(TAG, "内部更新锁定解除");
        }, 5000);

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(this);

        // 震动反馈
        if (prefs.getBoolean("vibrate_key", false)) {
            vibrate();
        }

        // 睡眠模式模拟点击
        if (prefs.getBoolean("bedtime_key", true)) {

            Log.d(TAG, "执行睡眠模式模拟点击");

            toggleBedtimeMode();

            return;
        }

        // 普通 API 模式
        mNotificationManager
                .setInterruptionFilter(dndState);

        Log.d(TAG,
                "常规 API 勿扰模式设置完成: "
                        + dndState);
    }

    // =========================================
    // 睡眠模式模拟点击
    // =========================================
    
    private void toggleBedtimeMode() {
    DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();

    if (serv == null) {
        Log.d(TAG, "AccessibilityService 未连接");
        return;
    }

    new Thread(() -> {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "dnd:sync"
            );

            // 唤醒屏幕
            wakeLock.acquire(5000L);
            Thread.sleep(1000);

            // 下拉控制中心
            serv.swipeDown();
            Log.d(TAG, "执行手势下拉");
            Thread.sleep(1200);

            // 🎯 从本地缓存读取手机端发过来的开关状态
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean isPhonePowerSaveOpen = prefs.getBoolean("phone_wear_power_save_state", false);

            if (isPhonePowerSaveOpen) {
                // 🚀 剧本 A：手机省电模式打开了
                Log.d(TAG, "手机端省电开关联动：准备先点击 (50%, 80%)，150ms后点击 (50%, 40%)");
                
                // 1. 先点击 80% 高度地方 (0ms 立即发射)
                serv.clickIconAt80Percent(0);
                
                // 2. 过 150ms 自动发射点击原有的 40% 高度地方
                serv.clickIcon1_2(150);
                
                // 给排队的手势留出执行时间
                Thread.sleep(500); 
            } else {
                // 🚀 剧本 B：开关没有打开
                Log.d(TAG, "手机端省电开关未联动：仅点击原设定 (50%, 40%) 的位置");
                
                // 只点击原有的 40% 高度地方
                serv.clickIcon1_2(0);
                
                Thread.sleep(400);
            }

            // 返回桌面
            serv.goBack();

        } catch (Exception e) {
            Log.e(TAG, "toggleBedtimeMode 异常", e);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }).start();
}
     
   

    // =========================================
    // 震动
    // =========================================
    private void vibrate() {

        Vibrator v =
                (Vibrator)
                        getSystemService(Context.VIBRATOR_SERVICE);

        if (v != null) {

            v.vibrate(
                    VibrationEffect.createOneShot(
                            50,
                            VibrationEffect.DEFAULT_AMPLITUDE
                    )
            );
        }
    }
        }
