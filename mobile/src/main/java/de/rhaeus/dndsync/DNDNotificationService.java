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

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean("dnd_sync_key", true)) {

            Log.d(TAG, "启动初始同步 + handshake");

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

        Log.d(TAG, "FILTER CHANGED: " + interruptionFilter);

        if (DNDSyncListenerService.isInternalUpdate) {
            return;
        }

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean("dnd_sync_key", true)) {

            new Thread(() ->
                    sendDNDSync(interruptionFilter)
            ).start();
        }
    }

    // =========================
    // 同步核心逻辑
    // =========================
    private void sendDNDSync(int dndState) {

        Log.d(TAG, "开始同步流程，状态值: " + dndState);

        try {

            Set<Node> nodes = Tasks.await(
                    Wearable.getNodeClient(this)
                            .getConnectedNodes()
            );

            if (nodes != null && !nodes.isEmpty()) {

                Log.d(TAG, "NodeClient 发现节点: " + nodes.size());
                sendToNodes(nodes, dndState);
                return;

            }

            Log.d(TAG, "NodeClient 空，使用 Capability ALL");

            CapabilityInfo capabilityInfo = Tasks.await(
                    Wearable.getCapabilityClient(this)
                            .getCapability(
                                    DND_SYNC_CAPABILITY_NAME,
                                    CapabilityClient.FILTER_ALL
                            )
            );

            Set<Node> capNodes = capabilityInfo.getNodes();

            if (capNodes != null && !capNodes.isEmpty()) {

                Log.d(TAG, "Capability 发现节点: " + capNodes.size());
                sendToNodes(capNodes, dndState);
                return;
            }

            Log.e(TAG, "无节点，执行 handshake");
            forceHandshake();

        } catch (Exception e) {
            Log.e(TAG, "同步失败", e);
        }
    }

    // =========================
    // 发送
    // =========================
    private void sendToNodes(Set<Node> nodes, int dndState) {

        if (nodes == null || nodes.isEmpty()) {
            Log.d(TAG, "sendToNodes 空节点");
            return;
        }

        byte[] data = new byte[]{(byte) dndState};

        for (Node node : nodes) {

            Log.d(TAG,
                    "发送 -> " + node.getDisplayName()
                            + " nearby=" + node.isNearby()
                            + " state=" + dndState);

            Task<Integer> task =
                    Wearable.getMessageClient(this)
                            .sendMessage(
                                    node.getId(),
                                    DND_SYNC_MESSAGE_PATH,
                                    data
                            );

            task.addOnSuccessListener(v ->
                    Log.d(TAG, "发送成功 -> " + node.getDisplayName()));

            task.addOnFailureListener(e ->
                    Log.e(TAG, "发送失败 -> " + node.getDisplayName()));
        }
    }

    // =========================
    // handshake 唤醒
    // =========================
    private void forceHandshake() {

        new Thread(() -> {
            try {

                Log.d(TAG, "执行 handshake");

                Set<Node> nodes = Tasks.await(
                        Wearable.getNodeClient(this)
                                .getConnectedNodes()
                );

                for (Node node : nodes) {

                    Wearable.getMessageClient(this)
                            .sendMessage(
                                    node.getId(),
                                    "/handshake",
                                    new byte[]{1}
                            );

                    Log.d(TAG,
                            "Handshake -> " + node.getDisplayName());
                }

            } catch (Exception e) {
                Log.e(TAG, "Handshake失败", e);
            }
        }).start();
    }
}
