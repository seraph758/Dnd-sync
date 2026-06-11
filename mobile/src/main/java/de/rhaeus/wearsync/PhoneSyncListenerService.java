package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("phone".equalsIgnoreCase(sender)) return; // 过滤回环

            // 1️⃣ 勿扰板块：保持原樣不動
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndVal);
                        new java.util.Timer().schedule(new java.util.TimerTask() {
                            @Override
                            public void run() { isInternalUpdate = false; }
                        }, 1500);
                    }
                }
                return;
            }

            // 2️⃣ 闹钟控制板块：保持原樣不動
            if ("alarm_control".equalsIgnoreCase(type)) {
                if ("DISMISS".equalsIgnoreCase(action)) {
                    if (PhoneSyncNotificationService.dismissPendingIntent != null) {
                        PhoneSyncNotificationService.dismissPendingIntent.send();
                    }
                } else if ("SNOOZE".equalsIgnoreCase(action)) {
                    if (PhoneSyncNotificationService.snoozePendingIntent != null) {
                        PhoneSyncNotificationService.snoozePendingIntent.send();
                    }
                }
                return;
            }

            // 3️⃣ 📸 [全域相機控制模塊通訊咬合鏈]
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [中轉接收] 收到手錶端相機動作 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    // 透過引導跳板頂出前台豁免權
                    Intent bridgeIntent = new Intent(this, PhoneCameraBridgeActivity.class);
                    bridgeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(bridgeIntent);
                }
                else if ("WATCH_READY".equalsIgnoreCase(action)) {
                    // 🎯 痛點二修復：必須把手錶發送端的節點 NodeId 提取出來傳遞下去，否則長連接通道因找不到藍牙對端而一片黑！
                    String remoteNodeId = messageEvent.getSourceNodeId();
                    Log.d(TAG, "🤝 [握手橋接] 提取到手錶端有效 NodeId: " + remoteNodeId + "，交由 Service 點火。");

                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("WATCH_READY");
                    svc.putExtra("node_id", remoteNodeId); // 注入 NodeId
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc); 
                    } else {
                        startService(svc);
                    }
                }  
                else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("STOP_CAMERA");
                    startService(svc);
                } 
                else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                    // 🎯 痛點一修復：直接將拍照動作安全投遞給本地 CameraService
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("TAKE_PICTURE"); 
                    startService(svc);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手機中轉解析異常", e);
        }
    }
}
