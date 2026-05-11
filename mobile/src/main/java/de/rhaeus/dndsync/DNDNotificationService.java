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
            Log.d("DNDSync_Debug", "服务已连接 - 初始状态: " + currentFilter);
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isLogEnabled = prefs.getBoolean("enable_debug_log", false);
        
        if (isLogEnabled) {
            Log.d("DNDSync_Debug", ">>> 启动同步流程，目标状态码: " + dndState);
        }

        try {
            // 1. 优先获取所有物理连接节点
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());

            if (nodes != null && !nodes.isEmpty()) {
                sendToNodes(nodes, dndState, isLogEnabled);
            } else {
                // 2. 物理节点为空，尝试通过 Capability 查找 (FILTER_ALL 范围更大)
                CapabilityInfo capabilityInfo = Tasks.await(
                        Wearable.getCapabilityClient(this)
                                .getCapability(DND_SYNC_CAPABILITY_NAME, CapabilityClient.FILTER_ALL));
                
                Set<Node> capNodes = capabilityInfo.getNodes();
                if (capNodes != null && !capNodes.isEmpty()) {
                    if (isLogEnabled) Log.d("DNDSync_Debug", "NodeClient为空，通过Capability发现设备");
                    sendToNodes(capNodes, dndState, isLogEnabled);
                } else {
                    if (isLogEnabled) Log.e("DNDSync_Debug", "未发现任何可用设备，尝试握手唤醒");
                    forceHandshake();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "同步异常: " + e.getMessage());
        }
    }

    private void sendToNodes(Collection<Node> nodes, int dndState, boolean isLogEnabled) {
        String modeDesc = (dndState == 2) ? "开启勿扰" : (dndState == 1 ? "关闭勿扰" : "其它(" + dndState + ")");
        
        for (Node node : nodes) {
            if (isLogEnabled) {
                Log.d("DNDSync_Debug", "╔════════ 发送同步请求 ════════╗");
                Log.d("DNDSync_Debug", "║ 目标设备: " + node.getDisplayName());
                Log.d("DNDSync_Debug", "║ 动作模式: " + modeDesc);
                Log.d("DNDSync_Debug", "║ 物理判定: " + (node.isNearby() ? "附近 (Nearby)" : "远程 (Remote)"));
            }

            byte[] data = new byte[]{(byte) dndState};
            
            Task<Integer> task = Wearable.getMessageClient(this)
                    .sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data);

            if (isLogEnabled) {
                task.addOnSuccessListener(v -> Log.d("DNDSync_Debug", "║ 发送结果: 🚀 成功提交系统队列"))
                    .addOnFailureListener(e -> Log.e("DNDSync_Debug", "║ 发送结果: ❌ 失败: " + e.getMessage()))
                    .addOnCompleteListener(v -> Log.d("DNDSync_Debug", "╚══════════════════════════════╝"));
            }
        }
    }

    private void forceHandshake() {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes != null) {
                    for (Node node : nodes) {
                        Wearable.getMessageClient(this).sendMessage(node.getId(), "/handshake", new byte[]{1});
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Handshake 失败", e);
            }
        }).start();
    }
}
