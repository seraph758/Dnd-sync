package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
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

    // 🎯 獲取與 MainFragment.kt 一致的專屬 SharedPreferences 實例
    private SharedPreferences getDndSyncPreferences() {
        return getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

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
        SharedPreferences prefs = getDndSyncPreferences();
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

        SharedPreferences prefs = getDndSyncPreferences();

        // 🌟 1. 檢查手機鬧鐘連動總開關
        boolean alarmMasterSwitch = prefs.getBoolean("custom_alarm_sync_master_switch", false);
        if (!alarmMasterSwitch) {
            return; // 總開關關閉，直接攔截不對接
        }

        // 🌟 2. 進行時鐘應用包名白名單精準校驗
        String packageName = sbn.getPackageName().toLowerCase();
        String defaultClockPackages = "com.google.android.deskclock,com.sec.android.app.clockpackage,com.android.deskclock";
        String allowedClockStr = prefs.getString("custom_allowed_clock_packages", defaultClockPackages).toLowerCase();

        boolean isAllowedPackage = false;
        String[] packageArray = allowedClockStr.split(",");
        for (String pkg : packageArray) {
            String trimmedPkg = pkg.trim();
            if (!trimmedPkg.isEmpty() && packageName.contains(trimmedPkg)) {
                isAllowedPackage = true;
                break;
            }
        }

        if (!isAllowedPackage) {
            Log.d(TAG, "🛑 [包名攔截] 應用: " + packageName + " 不在時鐘白名單內");
            return;
        }

        // 🌟 3. 包名驗證通過，進一步實施二級 Category 通知類型過濾
        String category = notification.category == null ? "none" : notification.category;
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

        if (!shouldSync) {
            Log.d(TAG, "🛑 [類型攔截] 包名正確，但 Category [" + category + "] 已被用戶關閉。");
            return;
        }

        // ✨ 雙重驗證完美放行，推送給手錶
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
