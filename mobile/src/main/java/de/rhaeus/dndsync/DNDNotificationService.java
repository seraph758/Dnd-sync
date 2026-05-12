package de.rhaeus.dndsync;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.List;

public class DNDNotificationService extends NotificationListenerService {

    private static final String TAG = "DNDNotificationService";

    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    public static boolean running = false;

    // 防止重复触发 DataLayer fallback
    private volatile boolean dataLayerFallbackTriggered = false;

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

        if (prefs.getBoolean("enable_debug_log", false)) {

            Log.d(
                    "DNDSync_Debug",
                    "服务已连接 - 当前状态: " + currentFilter
            );
        }

        if (prefs.getBoolean("dnd_sync_key", true)) {

            // 唤醒 DataLayer
            forceHandshake();

            // 初始同步
            new Thread(() ->
                    sendDNDSync(currentFilter)
            ).start();
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();

        Log.d(TAG, "listener disconnected");

        running = false;

        try {

            requestRebind(
                    new ComponentName(
                            this,
                            DNDNotificationService.class
                    )
            );

        } catch (Exception e) {

            Log.e(TAG, "requestRebind 失败", e);
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {

        Log.d(TAG, "FILTER CHANGED: " + interruptionFilter);

        if (DNDSyncListenerService.isInternalUpdate) {

            Log.d(TAG, "内部更新拦截");

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

    // =====================================================
    // 核心同步逻辑
    // =====================================================
    private void sendDNDSync(int dndState) {

        Log.d(TAG, "开始同步 DND: " + dndState);

        dataLayerFallbackTriggered = false;

        byte[] data = new byte[]{(byte) dndState};

        // =====================================================
        // 1. 优先 MessageClient（实时）
        // =====================================================
        Wearable.getNodeClient(this)
                .getConnectedNodes()
                .addOnSuccessListener(nodes -> {

                    if (nodes != null && !nodes.isEmpty()) {

                        Log.d(
                                TAG,
                                "发现节点数量: " + nodes.size()
                        );

                        for (Node node : nodes) {

                            Log.d(
                                    TAG,
                                    "发送至: "
                                            + node.getDisplayName()
                                            + " | nearby="
                                            + node.isNearby()
                            );

                            Wearable.getMessageClient(this)
                                    .sendMessage(
                                            node.getId(),
                                            DND_SYNC_MESSAGE_PATH,
                                            data
                                    )

                                    .addOnSuccessListener(v -> {

                                        Log.d(
                                                TAG,
                                                "实时同步成功 -> "
                                                        + node.getDisplayName()
                                        );

                                        // 成功后恢复 fallback 状态
                                        dataLayerFallbackTriggered = false;
                                    })

                                    .addOnFailureListener(e -> {

                                        Log.e(
                                                TAG,
                                                "实时同步失败: "
                                                        + e.getMessage()
                                        );

                                        // fallback 防抖
                                        if (!dataLayerFallbackTriggered) {

                                            dataLayerFallbackTriggered = true;

                                            Log.d(
                                                    TAG,
                                                    "降级使用 DataClient"
                                            );

                                            sendToDataLayer(dndState);
                                        }
                                    });
                        }

                    } else {

                        Log.d(
                                TAG,
                                "未发现节点，直接使用 DataClient"
                        );

                        if (!dataLayerFallbackTriggered) {

                            dataLayerFallbackTriggered = true;

                            sendToDataLayer(dndState);
                        }
                    }
                })

                .addOnFailureListener(e -> {

                    Log.e(
                            TAG,
                            "NodeClient 获取失败",
                            e
                    );

                    if (!dataLayerFallbackTriggered) {

                        dataLayerFallbackTriggered = true;

                        sendToDataLayer(dndState);
                    }
                });
    }

    // =====================================================
    // DataLayer fallback
    // =====================================================
    private void sendToDataLayer(int dndState) {

        Log.d(
                TAG,
                "开始写入 DataLayer: " + dndState
        );

        // 使用时间戳避免数据覆盖
        PutDataMapRequest request =
                PutDataMapRequest.create(
                        "/dnd_state/" + System.currentTimeMillis()
                );

        request.getDataMap().putInt(
                "dnd",
                dndState
        );

        request.getDataMap().putLong(
                "time",
                System.currentTimeMillis()
        );

        PutDataRequest putDataRequest =
                request.asPutDataRequest();

        putDataRequest.setUrgent();

        Wearable.getDataClient(this)
                .putDataItem(putDataRequest)

                .addOnSuccessListener(dataItem ->

                        Log.d(
                                TAG,
                                "DataLayer 同步成功: "
                                        + dataItem.getUri()
                        )
                )

                .addOnFailureListener(e ->

                        Log.e(
                                TAG,
                                "DataLayer 同步失败",
                                e
                        )
                );
    }

    // =====================================================
    // 强制唤醒 DataLayer
    // =====================================================
    private void forceHandshake() {

        new Thread(() -> {

            try {

                List<Node> nodes = Tasks.await(
                        Wearable.getNodeClient(this)
                                .getConnectedNodes()
                );

                if (nodes == null || nodes.isEmpty()) {

                    Log.d(TAG, "Handshake: 无节点");

                    return;
                }

                for (Node node : nodes) {

                    Wearable.getMessageClient(this)
                            .sendMessage(
                                    node.getId(),
                                    "/handshake",
                                    new byte[]{1}
                            );

                    Log.d(
                            TAG,
                            "Handshake sent -> "
                                    + node.getDisplayName()
                    );
                }

            } catch (Exception e) {

                Log.e(TAG, "Handshake 失败", e);
            }

        }).start();
    }
}
