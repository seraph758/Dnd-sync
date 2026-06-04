package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDNotificationService extends NotificationListenerService implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSync_PhoneSource";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;
    private int lastSentDndState = -1;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        Wearable.getMessageClient(this).addListener(this);
        syncCurrentDndState();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        Wearable.getMessageClient(this).removeListener(this);
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (interruptionFilter == lastSentDndState) return; 
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dnd_sync_switch", true)) {
            lastSentDndState = interruptionFilter;
            sendJsonMessage(buildDndJson(interruptionFilter));
        }
    }

    public void syncCurrentDndState() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                int currentFilter = nm.getCurrentInterruptionFilter();
                lastSentDndState = currentFilter;
                sendJsonMessage(buildDndJson(currentFilter));
            }
        } catch (Exception e) {}
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                if ("alarm".equalsIgnoreCase(json.optString("type", "")) && "dismiss".equalsIgnoreCase(json.optString("alarmAction", ""))) {
                    if (currentAlarmNotification != null) {
                        Notification notification = currentAlarmNotification.getNotification();
                        if (notification != null && notification.actions != null) {
                            for (Notification.Action act : notification.actions) {
                                String btnText = act.title.toString().toLowerCase();
                                if (btnText.contains("关闭") || btnText.contains("停止") || btnText.contains("清除") 
                                    || btnText.contains("dismiss") || btnText.contains("stop") || btnText.contains("close")) {
                                    act.actionIntent.send();
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {}
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        String packageName = sbn.getPackageName().toLowerCase();
        String category = notification.category == null ? "none" : notification.category;

        boolean isClockApp = packageName.contains("clock") || packageName.contains("alarm") || Notification.CATEGORY_ALARM.equals(category);
        if (!isClockApp) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean allowAlarmType = prefs.getBoolean("sync_category_alarm", true);
        boolean allowEventType = prefs.getBoolean("sync_category_event", false);
        boolean allowReminderType = prefs.getBoolean("sync_category_reminder", false);

        boolean shouldSync = false;
        if (Notification.CATEGORY_ALARM.equals(category) && allowAlarmType) {
            shouldSync = true;
        } else if (Notification.CATEGORY_EVENT.equals(category) && allowEventType) {
            shouldSync = true;
        } else if (Notification.CATEGORY_REMINDER.equals(category) && allowReminderType) {
            shouldSync = true;
        } else if ("none".equals(category)) {
            shouldSync = prefs.getBoolean("sync_category_unknown", false);
        }

        if (!shouldSync) return;

        currentAlarmNotification = sbn;
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "alarm");
            json.put("alarmAction", "ringing");
            sendJsonMessage(json.toString());
        } catch (Exception e) {}
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            currentAlarmNotification = null;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "stopped");
                sendJsonMessage(json.toString());
            } catch (Exception e) {}
        }
    }

    private String buildDndJson(int dndState) {
        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            return json.toString();
        } catch (Exception e) { return ""; }
    }

    private void sendJsonMessage(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        final byte[] data = jsonStr.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {}
        }).start();
    }
}
