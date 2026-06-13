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

            if ("phone".equalsIgnoreCase(sender)) return;

            // 1️⃣ 勿擾板塊
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 勿擾狀態值同步: " + dndVal);
                    }
                }
                return;
            }

            // 2️⃣ 自動化板塊
            if ("notification_action".equalsIgnoreCase(type)) {
                if ("SNOOZE".equalsIgnoreCase(action)) {
                    if (PhoneSyncNotificationService.snoozePendingIntent != null) {
                        PhoneSyncNotificationService.snoozePendingIntent.send();
                    }
                }
                return;
            }

            // 3️⃣ 相機控制板塊（嚴格走 Activity 前台跳板，突破 Android 14 後台 FGS 限制）
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [中轉接收] 收到動作 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    // 拉起 Activity 獲取前台權限
                    Intent mainIntent = new Intent(this, PhoneSyncMainActivity.class);
                    mainIntent.setAction("ACTION_START_CAMERA_FLOW");
                    mainIntent.putExtra("camera_action", action);
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                      | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(mainIntent);
                } else {
                    // WATCH_READY, TAKE_PICTURE, STOP_CAMERA 直接送至 Service 處理
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction(action);
                    startService(svc);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析手錶訊息失敗", e);
        }
    }
}
