package de.rhaeus.dndsync;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
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

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "WearSync 手表端监听已就绪");
        running = true;
    }

    @Override
    public void onListenerDisconnected() {
        Log.d(TAG, "WearSync 手表端监听断开");
        running = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 这里可以根据手表本地的具体通知中转需要编写逻辑
        // 已经彻底去除了对旧变量 isInternalUpdate 和 stopLoopVibration() 的错误引用
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 通知移除时的逻辑
    }
}
