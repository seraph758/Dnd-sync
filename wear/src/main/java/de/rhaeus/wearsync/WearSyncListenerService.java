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
                Log.d(TAG, "📸 混合架构成功！手表感知到手机大数据通道已开启，启动异步异流接管");
                
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
                            Log.d(TAG, "📸 手表成功解析一帧 Channel 图片，已投递至界面");
                        }
                        is.close();
                        bos.close();
                    } catch (Exception e) {
                        Log.e(TAG, "手表端读取相机 Channel 数据流异常", e);
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

            // 1️⃣ 勿扰/省电/睡眠 状态变更总线接收器
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                boolean isVibrate = json.optBoolean("is_vibrate", false); // 提取手机传来的震动开关值

                if (dndVal != -1) {
                    // A. 先同步切换手表硬件勿扰
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 手表硬件成功响应手机勿扰状态更新: " + dndVal);
                    }

                    // B. 执行连动震动逻辑
                    if (isVibrate) {
                        try {
                            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                            if (vibrator != null && vibrator.hasVibrator()) {
                                vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE));
                                Log.d(TAG, "📳 勿扰状态变更：成功驱动手表硬件产生同步震动提示");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "手表硬件驱动震动发生异常", e);
                        }
                    }

                    // C. 省电模式控制分支：直接执行底层的 Setting Write，无需无障碍干预
                    if ("START_POWER_SAVING_DIRECT".equalsIgnoreCase(action)) {
                        try {
                            boolean res = Settings.Global.putInt(getContentResolver(), "low_power", 1);
                            Log.d(TAG, "🔋 执行 Setting Write 控制：秒切进入系统低功耗省电模式 结果: " + res);
                        } catch (Exception e) {
                            Log.e(TAG, "Setting Write 写入 low_power 失败，请确保下发了 WRITE_SECURE_SETTINGS 授权命令", e);
                        }
                        return; // 纯底层直接写库完毕，拦截退出
                    }

                    if ("STOP_ALL_MODES".equalsIgnoreCase(action)) {
                        try {
                            Settings.Global.putInt(getContentResolver(), "low_power", 0);
                            Log.d(TAG, "🔋 关闭低功耗省电模式，还原底层 low_power=0");
                        } catch (Exception ignored) {}
                    }

                    // D. 睡眠模式控制分支：强制点亮屏幕并驱动无障碍高级下拉宏指令
                    if ("START_SLEEP_MACRO".equalsIgnoreCase(action)) {
                        try {
                            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null && !pm.isInteractive()) {
                                android.os.PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | 
                                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                                        "WearSync:WakeForMacro"
                                );
                                wakeLock.acquire(5000); // 强力唤醒亮屏5秒
                                Log.d(TAG, "⚡ 检测到手表黑屏，已成功为高级睡眠巨集强行亮屏");
                                Thread.sleep(350); // 给图形渲染核心留出渲染准备时间
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "强行亮屏唤醒失败", e);
                        }

                        // 下发命令至无障碍高级组件
                        WearSyncAccessService accessService = WearSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            accessService.triggerBedtimeMacro(true);
                            Log.d(TAG, "🚀 已驱动高级无障碍服务唤醒快捷设置面板（openQuickSettings）并进行宏点击");
                        } else {
                            Log.e(TAG, "❌ 睡眠自动化宏触发失败：无障碍接管服务未被用户激活");
                        }
                    }
                }
                return;
            }

            // 2️⃣ ⏰ 闹钟控制模块（原封不动救回，全套业务链路安全重构！）
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 收到手机端闹钟响铃信令，准备在手表端拉起全屏强弹窗控制交互");
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 收到手机端闹钟断开解除信令，发送本地广播撤销全屏强弹窗 UI");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }

            // 3️⃣ 相机信令控制模块（安全保留）
            if ("camera_action".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到相机唤醒指令，直接拉起 WearCameraActivity 界面");
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(camIntent);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手表端解析中央指令集协议层报文异常", e);
        }
    }
}
