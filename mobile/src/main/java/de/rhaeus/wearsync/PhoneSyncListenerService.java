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

            // 1️⃣ 勿扰板块 (完全保留不碰)
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 [同步成功] 已同步手錶端的勿擾狀態值: " + dndVal);
                    }
                }
                return;
            }

            // 2️⃣ 自动化板块 (完全保留不碰)
            if ("notification_action".equalsIgnoreCase(type)) {
                if ("SNOOZE".equalsIgnoreCase(action)) {
                    if (PhoneSyncNotificationListenerService.snoozePendingIntent != null) {
                        PhoneSyncNotificationListenerService.snoozePendingIntent.send();
                        Log.d(TAG, "🎯 [自动化成功] 已代用户点击手机通知栏「延后/稍后提醒」按钮");
                    } else {
                        Log.w(TAG, "⚠️ 触发点击失败：手机端暂未捕获到合法的延后 PendingIntent");
                    }
                }
                return;
            }

            // 3️⃣ 相机控制板块
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [中轉接收] 收到手錶端相機動作 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⚡ [換思路] 接收到 START_CAMERA，正在強制拉起手機前台 UI 以獲取前台豁免權...");
                    Intent mainIntent = new Intent(this, PhoneSyncMainActivity.class);
                    mainIntent.setAction("ACTION_SHOW_CAMERA_UI");
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                      | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(mainIntent);
                } 
                else {
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
