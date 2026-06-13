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

            // ================= 1️⃣ 勿擾同步模塊（嚴格保留，絕不改動） =================
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

            // ================= 2️⃣ 鬧鐘延後點擊模塊（嚴格保留，絕不改動） =================
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

            // ================= 3️⃣ 相機模塊：完美融入能亮屏的舊版純淨數據傳輸流協議 =================
            if ("camera_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "📸 [中轉接收] 收到相機動作 Action: " + action);

                if ("START_CAMERA".equalsIgnoreCase(action)) {
                    Log.d(TAG, "🚀 [穿透啟動] 正在喚醒手機前台 Activity 以獲取 OxygenOS 前台啟動豁免權...");

                    // 1. 先把手機端 Activity 提權到最前台，幫相機前台服務拿到系統豁免准入資格
                    Intent intent = new Intent(this, PhoneSyncMainActivity.class);
                    intent.setAction("ACTION_START_CAMERA_FLOW");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                  | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                  | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);

                    // 2. 隨後緊接著調用 startForegroundService 啟動相機背景 FGS 服務，掛上通知欄防止被強殺
                    Intent svc = new Intent(this, PhoneSyncCameraService.class);
                    svc.setAction("START_CAMERA");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } else {
                    // 🎯 核心控制：手錶發來的後續指令（TAKE_PICTURE / STOP_CAMERA）只是狀態控制，
                    // 必須使用最普通的 startService 投遞，絕對不能調用 startForegroundService，
                    // 這樣既能精準傳達拍照/停止動作，又完美繞過 Android 14 系統的前台超時斷頭台強殺！
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
