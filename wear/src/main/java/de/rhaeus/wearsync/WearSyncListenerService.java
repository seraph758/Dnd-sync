package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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

    // 🚀 聲明獨立的 ChannelCallback 實例，完美避開單繼承語法限制
    private final ChannelClient.ChannelCallback cameraChannelCallback = new ChannelClient.ChannelCallback() {
        @Override
        public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
            if ("/wear-camera-stream".equals(channel.getPath())) {
                Log.d(TAG, "📸 混合架構成功！手錶感知到手機大數據通道已開啟，啟動異步異流接管");
                
                // 1. 強制拉起手錶相機 UI 介面
                Intent intent = new Intent(WearSyncListenerService.this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);

                // 2. 異步異流接管讀取網路位元組
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
                            // 3. 透過本地廣播發送給 WearCameraActivity
                            Intent broadcast = new Intent("de.rhaeus.wearsync.LOCAL_WEAR_CAMERA_STREAM");
                            broadcast.putExtra("WEAR_JPEG", rawJpeg);
                            broadcast.setPackage(getPackageName());
                            sendBroadcast(broadcast);
                            Log.d(TAG, "📸 手錶成功解析一幀 Channel 圖片，位元組大小: " + rawJpeg.length + "，已投遞至界面");
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
        public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
            Log.d(TAG, "📸 相機數據通道關閉");
        }

        @Override
        public void onInputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {}

        @Override
        public void onOutputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 🚀 傳入正確的 callback 實例
        Wearable.getChannelClient(this).registerChannelCallback(cameraChannelCallback);
    }

    @Override
    public void onDestroy() {
        // 🚀 註銷對應的 callback 實例
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

            // 1️⃣ 勿扰模块
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 手表硬件成功响应手机勿扰状态更新: " + dndVal);
                    }
                }
                return;
            }

            // 2️⃣ 闹钟模块
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

            // 3️⃣ 相机模块
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
