package de.rhaeus.dndsync;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.Set;
import java.util.concurrent.ExecutionException;

public class DNDNotificationService extends NotificationListenerService {

    private static final String TAG = "DNDNotificationService";
    private static final String DND_SYNC_CAPABILITY_NAME = "dnd_sync";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "listener connected");
        running = true;

        int currentFilter = getCurrentInterruptionFilter();
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // 增加：连接成功时也受开关控制打印日志
        if (prefs.getBoolean("enable_debug_log", false)) {
            Log.d("DNDSync_Debug", "服务已连接 - 当前 DND 状态: " + currentFilter);
        }

        if (prefs.getBoolean("dnd_sync_key", true)) {
            new Thread(() -> sendDNDSync(currentFilter)).start();
        }
    }

    @Override
    public void onListenerDisconnected() {
        Log.d(TAG, "listener disconnected");
        running = false;
        try {
            requestRebind(new ComponentName(this, DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (DNDSyncListenerService.isInternalUpdate) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            new Thread(() -> sendDNDSync(interruptionFilter)).start();
        }
    }

    private void sendDNDSync(int dndState) {
        CapabilityInfo capabilityInfo = null;
        try {
            capabilityInfo = Tasks.await(
                    Wearable.getCapabilityClient(this).getCapability(
                            DND_SYNC_CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE));
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Error getting capability", e);
            return;
        }

        if (capabilityInfo == null) return;

        Set<Node> connectedNodes = capabilityInfo.getNodes();
        if (connectedNodes.isEmpty()) return;

        // 读取日志开关
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isLogEnabled = sharedPreferences.getBoolean("enable_debug_log", false);

        for (Node node : connectedNodes) {
            // 【核心修改】：已移除 isNearby() 判定，现在只要在 connectedNodes 里就发送
            
            if (isLogEnabled) {
                Log.d("DNDSync_Debug", "发送信号至: " + node.getDisplayName() + 
                      " | 状态: " + dndState + " | 实际Nearby=" + node.isNearby()); 
            }

            byte[] data = new byte[1];
            data[0] = (byte) dndState;

            Task<Integer> sendTask = Wearable.getMessageClient(this)
                    .sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data);
            
            if (isLogEnabled) {
                sendTask.addOnSuccessListener(v -> Log.d("DNDSync_Debug", "Google Play 服务已受理该请求"));
                sendTask.addOnFailureListener(e -> Log.e("DNDSync_Debug", "发送失败: " + e.getMessage()));
            }
        }
    }
}
