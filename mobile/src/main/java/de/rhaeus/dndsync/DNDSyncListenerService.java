package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            if (data == null) return;

            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                if ("phone".equalsIgnoreCase(sender)) return;

                // 處理手錶端反向傳回的勿擾同步
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                    
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            Log.d(TAG, "📥 收到手錶反向勿擾請求 -> 更新手機系統勿擾狀態: " + dndValue);
                            mNotificationManager.setInterruptionFilter(dndValue);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析手錶回傳數據失敗", e);
            }
        }
    }

    private void sendExitSignalToWear() {
        new Thread(() -> {
            try {
                JSONObject exitJson = new JSONObject();
                exitJson.put("sender", "phone");
                exitJson.put("type", "alarm");
                exitJson.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                exitJson.put("timestamp", System.currentTimeMillis());
                byte[] data = exitJson.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                    Log.d(TAG, "📤 [強制退出] 已向手錶端強發 FORCE_STOP_WEAR_ALARM 退出信號");
                }
            } catch (Exception e) {
                Log.e(TAG, "向手錶發送退出信號失敗", e);
            }
        }).start();
    }
}
