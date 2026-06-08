package de.rhaeus.wearsync;

import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        // 嚴格校驗通道
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            Log.d(TAG, "📥 原始通道收到消息 -> type: " + type + ", action: " + action);

            // ==================== ⏰ 鬧鐘模塊：回歸最初成功版本 ====================
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ [🎯 執行還原邏輯] 收到啟動命令，直接暴力拉起 WearAlarmActivity");
                    
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    // 核心必備 Flag：確保 Service 能夠直接拉起 Activity 畫面
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                    
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    Log.d(TAG, "⏰ 收到關閉命令，發送廣播通知關閉手錶響鈴 UI");
                    sendBroadcast(new Intent("de.rhaeus.wearsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }

            // ==================== 📸 相機模塊：回歸最初成功版本 ====================
            if ("camera_action".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Log.d(TAG, "📸 收到相機喚醒，直接拉起 WearCameraActivity");
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(camIntent);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手錶端原始解析崩潰", e);
        }
    }
}
