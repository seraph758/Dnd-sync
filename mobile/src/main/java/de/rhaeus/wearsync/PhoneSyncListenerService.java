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

            if ("phone".equalsIgnoreCase(sender)) return; // 過濾本地回環

            // ================= 1️⃣ 勿擾同步模塊（严格保留，絕不改動） =================
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndVal);
                        Log.d(TAG, "🌙 [同步成功] 已依手錶同步變更手機勿擾狀態: " + dndVal);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> isInternalUpdate = false, 1000);
                    }
                }
                return;
            }

            // ================= 2️⃣ 鬧鐘延後點擊模塊（严格保留，絕不改動） =================
            if ("alarm_action".equalsIgnoreCase(type)) {
                if ("SNOOZE_ALARM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ [收到指令] 手錶觸發了「延後手機鬧鐘」");
                    if (PhoneSyncNotificationService.snoozePendingIntent != null) {
                        PhoneSyncNotificationService.snoozePendingIntent.send();
                        Log.d(TAG, "🎯 [自動化成功] 已代用戶點擊手機通知欄延後按鈕");
                    } else {
                        Log.w(TAG, "⚠️ 觸發點击失敗：手機端暫未捕獲到合法的延後 PendingIntent");
                    }
                }
                return;
            }

// ================= 3️⃣ 相機模塊：解決氧OS後台啟動被默默丟棄的致命傷（嚴格保留原本邏輯） =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [中轉接收] 收到動作 Action: " + action);
            
                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [穿透啟動] 正在喚醒手機前台 Activity 以獲取 OxygenOS 前台啟動豁免權...");
                    
                    Intent intent = new Intent(this, PhoneSyncMainActivity.class);
                    intent.setAction("ACTION_START_CAMERA_FLOW");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                  | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                  | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                } else {
                    // WATCH_READY, TAKE_PICTURE, STOP_CAMERA 在 Activity 已經起來後，直接安全傳遞給 CameraService
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction(action);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "解析手錶訊息失敗", e);
        }
    }
}