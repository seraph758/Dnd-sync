package de.rhaeus.dndsync;

import android.content.ComponentName;
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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class DNDNotificationService extends NotificationListenerService {

    private static final String TAG = "DNDNotificationService";
    private static final String DND_SYNC_CAPABILITY_NAME = "dnd_sync";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    public static boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SERVICE CREATED");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "listener connected");
        running = true;

        int currentFilter = getCurrentInterruptionFilter();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean("enable_debug_log", false)) {
            Log.d("DNDSync_Debug", "服务连接 - 初始状态: " + currentFilter);
        }

        if (prefs.getBoolean("dnd_sync_key", true)) {
            forceHandshake();
            new Thread(() -> sendDNDSync(currentFilter)).start();
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        try {
            requestRebind(new ComponentName(this, DNDNotificationService.class));
        } catch (Exception e) {
            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (DNDSyncListenerService.isInternalUpdate) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_key", true)) {
            new Thread(() -> sendDNDSync(interruptionFilter)).start();
        }
    }

    private void sendDNDSync(int dndState) {
        try {
            // 获取物理连接节点 (返回的是 List)
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());

            if (nodes != null && !nodes.isEmpty()) {
                sendToNodes(nodes, dndState);
            } else {
                // Fallback 到 Capability (返回的是 Set)
                CapabilityInfo capabilityInfo = Tasks.await(
                        Wearable.getCapabilityClient(this)
                                .getCapability(DND_SYNC_CAPABILITY_NAME, CapabilityClient.FILTER_ALL));
                
                Set<Node> capNodes = capabilityInfo.getNodes();
                if (capNodes != null && !capNodes.isEmpty()) {
                    sendToNodes(capNodes, dndState);
                } else {
                    forceHandshake();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "同步异常: " + e.getMessage());
        }
    }

    // 使用 Collection 确保同时兼容 List 和 Set
    private void sendToNodes(Collection<Node> nodes, int dndState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isLogEnabled = prefs.getBoolean("enable_debug_log", false);

        for (Node node : nodes) {
            if (isLogEnabled) {
                Log.d("DNDSync_Debug", "发送至: " + node.getDisplayName() + " | Nearby=" + node.isNearby());
            }

            byte[] data = new byte[]{(byte) dndState};
            Wearable.getMessageClient(this).sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data);
        }
    }

    private void forceHandshake() {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/handshake", new byte[]{1});
                }
            } catch (Exception e) {
                Log.e(TAG, "Handshake 失败", e);
            }
        }).start();
    }
}
