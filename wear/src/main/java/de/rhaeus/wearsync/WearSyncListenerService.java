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
                Intent intent = new Intent(WearSyncListenerService.this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);

                new Thread(() -> {
                    try {
                        InputStream is = Tasks.await(Wearable.getChannelClient(WearSyncListenerService.this).getInputStream(channel));
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = is.read(buffer)) != -1) { bos.write(buffer, 0, len); }
                        byte[] rawJpeg = bos.toByteArray();
                        if (rawJpeg.length > 0) {
                            Intent broadcast = new Intent("de.rhaeus.wearsync.LOCAL_WEAR_CAMERA_STREAM");
                            broadcast.putExtra("WEAR_JPEG", rawJpeg);
                            broadcast.setPackage(getPackageName());
                            sendBroadcast(broadcast);
                        }
                        is.close(); bos.close();
                    } catch (Exception e) { Log.e(TAG, "大数据流通讯异常", e); }
                }).start();
            }
        }
    };

    @Override
    public void onCreate() { super.onCreate(); Wearable.getChannelClient(this).registerChannelCallback(cameraChannelCallback); }
    @Override
    public void onDestroy() { Wearable.getChannelClient(this).unregisterChannelCallback(cameraChannelCallback); super.onDestroy(); }

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

            Log.d(TAG, "📥 手表数据路由中心接收协议 -> type: " + type);

            // === [AI_SECURITY_FIREWALL: WEAR_DND_SCORE_MATRIX_ROUTINE] ===
            // 勿扰联动总线：查表得分法。严格处理勿扰“开启”与“联动全关闭”全场景，无任何子 if 比较
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                int score = json.optInt("switches_mask", 0);

                // 步骤 1: 优先双向同步手表硬件系统勿扰状态
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "📊 查表得分法[步骤1]: 硬件系统勿扰级强制同步完成 -> " + dndVal);
                    }
                }

                // 判断手机目前是开启还是关闭
                boolean isActivated = (dndVal == NotificationManager.INTERRUPTION_FILTER_PRIORITY || 
                                       dndVal == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                       dndVal == NotificationManager.INTERRUPTION_FILTER_ALARMS);

                if (isActivated) {
                    Log.d(TAG, "📊 查表得分法: 检测到勿扰【开启】，当前配置总分数 = " + score + "。开始直接映射顺序流...");
                    
                    // 步骤 2: 优先震动驱动映射 (分数含 4)
                    if (score == 4 || score == 5 || score == 6 || score == 7) {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null && vibrator.hasVibrator()) {
                            vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    }

                    // 步骤 3: 唤醒并驱动睡眠宏开启映射 (分数含 1)
                    if (score == 1 || score == 3 || score == 5 || score == 7) {
                        wakeScreenIfNeeded();
                        WearSyncAccessService accessService = WearSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            accessService.triggerBedtimeMacro(true); // 开启睡眠宏
                        } else { Log.e(TAG, "❌ 睡眠宏触发失败：无障碍接管服务未激活"); }
                    }

                    // 步骤 4: 驱动底层省电模式开启映射 (分数含 2)
                    if (score == 2 || score == 3 || score == 6 || score == 7) {
                        Settings.Global.putInt(getContentResolver(), "low_power", 1);
                    } else {
                        Settings.Global.putInt(getContentResolver(), "low_power", 0);
                    }

                } else {
                    Log.d(TAG, "📊 查表得分法: 检测到勿扰【关闭】，正在根据总分数 " + score + " 带上睡眠及省电进行同步关闭还原...");
                    
                    // 步骤 2: 联动关闭睡眠宏还原 (分数含 1)
                    if (score == 1 || score == 3 || score == 5 || score == 7) {
                        wakeScreenIfNeeded();
                        WearSyncAccessService accessService = WearSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            accessService.triggerBedtimeMacro(false); // 传入 false 执行关闭逻辑
                        }
                    }

                    // 步骤 3: 强制清零低功耗模式还原系统基准 (不论分数，安全清零底层省电状态)
                    Settings.Global.putInt(getContentResolver(), "low_power", 0);
                    Log.d(TAG, "📊 查表得分法: 联动关闭完成，系统全状态基准复原。");
                }
                return;
            }
            // === [AI_SECURITY_FIREWALL_END: WEAR_DND_SCORE_MATRIX_ROUTINE] ===

            // 2️⃣ ⏰ 闹钟交互弹窗控制总线
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }

            // 3️⃣ 远程相机拉起
            if ("camera_action".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(camIntent);
                }
                return;
            }

        } catch (Exception e) { Log.e(TAG, "流解析异常", e); }
    }

    private void wakeScreenIfNeeded() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                android.os.PowerManager.WakeLock wakeLock = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                        "WearSync:Wake"
                );
                wakeLock.acquire(3000);
                Thread.sleep(300);
            }
        } catch (Exception ignored) {}
    }
}
