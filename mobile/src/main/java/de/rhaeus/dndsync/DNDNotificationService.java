package de.rhaeus.dndsync;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
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

        // 重启或服务绑定后，立即同步当前手机 DND 状态到手表
        int currentFilter = getCurrentInterruptionFilter();
        Log.d(TAG, "onListenerConnected - 当前 DND 状态: " + currentFilter);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
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
            Log.d(TAG, "已请求 requestRebind");
        } catch (Exception e) {
            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        Log.d(TAG, "interruption filter changed to " + interruptionFilter);

        if (DNDSyncListenerService.isInternalUpdate) {
            Log.d(TAG, "檢測到內部同步觸發，攔截發送以防止死循環");
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

        if (capabilityInfo == null) {
            Log.d(TAG, "capabilityInfo is null");
            return;
        }

        Set<Node> connectedNodes = capabilityInfo.getNodes();
        if (connectedNodes.isEmpty()) {
            Log.d(TAG, "No reachable nodes with sync capability!");
            return;
        }

        for (Node node : connectedNodes) {
            if (node.isNearby()) {
                byte[] data = new byte[]{ (byte) dndState };   // 更简洁的写法

                Task<Integer> sendTask = Wearable.getMessageClient(this)
                        .sendMessage(node.getId(), DND_SYNC_MESSAGE_PATH, data);

                sendTask.addOnSuccessListener(integer -> 
                    Log.d(TAG, "send successful! Node: " + node.getId()));

                sendTask.addOnFailureListener(e -> 
                    Log.d(TAG, "send failed! Node: " + node.getId()));
            }
        }
    }
}
