package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
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

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final ChannelClient.ChannelCallback cameraChannelCallback = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
            if ("/wear-camera-stream".equals(channel.getPath())) {
                Log.d(TAG, "📸 混合架構成功！手錶感知到手機大數據通道已開啟，啟動異步異流接管");
                
                Intent intent = new Intent(WearSyncListenerService.this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);

                new Thread(() -> {
                    try {
                        InputStream is = Tasks.await(Wearable.getChannelClient(WearSyncListenerService.this).getInputStream(channel));
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            bos.write(buffer, 0, len);
                        }
                        byte[] rawJpeg = bos.toByteArray();
                        
                        if (rawJpeg.length > 0) {
                            Intent broadcast = new Intent("de.rhaeus.wearsync.LOCAL_WEAR_CAMERA_STREAM");
                            broadcast.putExtra("WEAR_JPEG", rawJpeg);
                            broadcast.setPackage(getPackageName());
                            sendBroadcast(broadcast);
                            Log.d(TAG, "📸 手錶成功解析一幀 Channel 圖片，已投遞至界面");
                        }
                        is.close();
                        bos.close();
                    } catch (Exception e) {
                        Log.e(TAG, "手錶端讀取相機 Channel 數據流異常", e);
                    }
                }).start();
            }
        }

        @Override
        public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {}
        @Override
        public void onInputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {}
        @Override
        public void onOutputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Wearable.getChannelClient(this).registerChannelCallback(cameraChannelCallback);
    }

    @Override
    public void onDestroy() {
        Wearable.getChannelClient(this).unregisterChannelCallback(cameraChannelCallback);
        super.onDestroy();
    }

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

            Log.d(TAG, "📥 手表端收到原始通道消息 -> type: " + type + ", action: " + action);

            // 1️⃣ 勿擾/睡眠/省電 狀態變更總線處理
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                boolean isVibrate = json.optBoolean("is_vibrate", false); // 讀取震動配置

                if (dndVal != -1) {
                    // A. 先同步切換手錶硬體勿擾
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 手表硬件成功响应手机勿扰状态更新: " + dndVal);
                    }

                    // B. 處理震動開關邏輯
                    if (isVibrate) {
                        try {
                            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                            if (vibrator != null && vibrator.hasVibrator()) {
                                vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE));
                                Log.d(TAG, "📳 勿擾狀態變更：觸發手錶同步震動提示");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "手錶執行震動失敗", e);
                        }
                    }

                    // C. 省電模式處理分支：走 Setting Write 底層修改系統數據庫，完全不使用無障礙
                    if ("START_POWER_SAVING_DIRECT".equalsIgnoreCase(action)) {
                        try {
                            boolean res = Settings.Global.putInt(getContentResolver(), "low_power", 1);
                            Log.d(TAG, "🔋 執行 Setting Write：開啟系統低功耗省電模式 結果: " + res);
                        } catch (Exception e) {
                            Log.e(TAG, "Setting Write 寫入 low_power 失敗，請確保授予了 WRITE_SECURE_SETTINGS 權限", e);
                        }
                        return; // 省電底層改完，直接攔截返回
                    }

                    if ("STOP_ALL_MODES".equalsIgnoreCase(action)) {
                        try {
                            Settings.Global.putInt(getContentResolver(), "low_power", 0);
                            Log.d(TAG, "🔋 關閉省電模式，還原系統資料庫 low_power=0");
                        } catch (Exception ignored) {}
                    }

                    // D. 睡眠模式處理分支：強制點亮螢幕 + 無障礙高級快捷欄下拉點擊
                    if ("START_SLEEP_MACRO".equalsIgnoreCase(action)) {
                        try {
                            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null && !pm.isInteractive()) {
                                android.os.PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | 
                                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                                        "WearSync:WakeForMacro"
                                );
                                wakeLock.acquire(5000); // 強制亮屏 5 秒
                                Log.d(TAG, "⚡ 檢測到黑屏休眠，成功為睡眠巨集強制喚醒亮屏");
                                Thread.sleep(350); // 留出 350ms 給系統圖形渲染核心準備 UI
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "強制亮屏發生異常", e);
                        }

                        // 通知無障礙服務執行高級下拉巨集
                        WearSyncAccessService accessService = WearSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            accessService.triggerBedtimeMacro(true);
                            Log.d(TAG, "🚀 已成功向無障礙核心投遞高級下拉點擊睡眠巨集指令");
                        } else {
                            Log.e(TAG, "❌ 睡眠巨集執行失敗：無障礙服務未授權開啟");
                        }
                    }
                }
                return;
            }

            // 2️⃣ 鬧鐘模組
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 收到手机闹钟响铃指令，准备拉起强弹窗");
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 收到手机关闭指令，发送广播销毁手表响铃 UI");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }

            // 3️⃣ 相機模組
            if ("camera_action".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到相机唤醒指令，直接拉起 WearCameraActivity");
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(camIntent);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手表端监听解析异常", e);
        }
    }
}
