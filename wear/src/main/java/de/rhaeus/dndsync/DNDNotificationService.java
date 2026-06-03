package de.rhaeus.dndsync;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDNotificationService extends NotificationListenerService {
    private static final String TAG = "WearSync_WearSource";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean running = false;
    private static Context appContext = null;

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "WearSync 手錶端監聽已就緒");
        running = true;
        appContext = getApplicationContext();
        sendDndJson(getCurrentInterruptionFilter());
    }

    @Override
    public void onListenerDisconnected() {
        running = false;
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (DNDSyncListenerService.isInternalUpdate) return;
        sendDndJson(interruptionFilter);
    }

    private void sendDndJson(int dndState) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "wear");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            json.put("timestamp", System.currentTimeMillis());
            sendStaticJsonMessage(appContext, json.toString());
        } catch (Exception e) {
            Log.e(TAG, "手錶勿擾打包失敗", e);
        }
    }

    // 🎯 供手錶 UI 介面「關閉鬧鐘」按鈕點擊調用
    public static void sendDismissAlarmRequest(Context context) {
        try {
            Log.d(TAG, "👉 用戶點擊手錶【關閉】：正在通知手機，並停止手錶震動...");
            
            // 1. 自覺停止手錶本體的持續震動
            DNDSyncListenerService.stopLoopVibration();
            
            // 2. 發射反向控制包給手機
            JSONObject json = new JSONObject();
            json.put("sender", "wear");
            json.put("type", "alarm");
            json.put("alarmAction", "dismiss"); // 行為：消除
            json.put("timestamp", System.currentTimeMillis());
            sendStaticJsonMessage(context != null ? context : appContext, json.toString());
        } catch (Exception e) {
            Log.e(TAG, "發送關閉請求失敗", e);
        }
    }

    // 🎯 供手錶 UI 介面「稍後再響/小睡」按鈕點擊調用
    public static void sendSnoozeAlarmRequest(Context context) {
        try {
            Log.d(TAG, "👉 用戶點擊手錶【小睡】：正在通知手機，並停止手錶震動...");
            
            // 1. 自覺停止手錶本體的持續震動
            DNDSyncListenerService.stopLoopVibration();
            
            // 2. 發射反向控制包給手機
            JSONObject json = new JSONObject();
            json.put("sender", "wear");
            json.put("type", "alarm");
            json.put("alarmAction", "snooze"); // 行為：延時小睡
            json.put("timestamp", System.currentTimeMillis());
            sendStaticJsonMessage(context != null ? context : appContext, json.toString());
        } catch (Exception e) {
            Log.e(TAG, "發送小睡請求失敗", e);
        }
    }

    private static void sendStaticJsonMessage(Context context, String jsonStr) {
        if (context == null || jsonStr == null || jsonStr.isEmpty()) return;
        final byte[] data = jsonStr.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                if (nodes == null || nodes.isEmpty()) return;
                for (Node node : nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "手錶發射異常", e);
            }
        }).start();
    }
}
