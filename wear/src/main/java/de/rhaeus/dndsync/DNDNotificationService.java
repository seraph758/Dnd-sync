package de.rhaeus.dndsync;

import android.app.NotificationManager;
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
    private static final String TAG = "WearSync_WearNotification";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    public static void sendDndReverseSyncToPhone(Context context, int interruptionFilter) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "wear");
            json.put("type", "dnd");
            json.put("dnd_profile_value", interruptionFilter); // 回归纯粹系统勿扰修改

            final byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            new Thread(() -> {
                try {
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                    for (Node n : nodes) {
                        Wearable.getMessageClient(context).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                    }
                    Log.d(TAG, "📤 手表成功反向向手机投递纯系统勿扰信令: " + interruptionFilter);
                } catch (Exception e) {
                    Log.e(TAG, "反向勿扰投递失败", e);
                }
            }).start();
        } catch (Exception ignored) {}
    }
}
