package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
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

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        // 註冊藍牙穿戴訊息監聽，接收來自手錶端的控制信號
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 穿戴互聯手機端發射服務已成功啟動並掛載監聽！");
    }

    @Override
    public void onListenerDisconnected() {
        Wearable.getMessageClient(this).removeListener(this);
        running = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (DNDSyncListenerService.isInternalUpdate) {
            DNDSyncListenerService.isInternalUpdate = false;
            return;
        }
        Log.d(TAG, "📱 偵測到手機系統勿擾模式變更: " + interruptionFilter + "，啟動穿戴發射鏈條");
        pushDndAndPowerStatusToWear(interruptionFilter);
    }

    private void pushDndAndPowerStatusToWear(int dndState) {
        SharedPreferences sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        boolean isDndSyncEnabled = sharedPreferences.getBoolean("dnd_sync_switch", true);
        
        if (!isDndSyncEnabled) {
            Log.d(TAG, "DND 同步開關未開啟，中斷向手錶推送狀態。");
            return;
        }

        // 讀取 UI 快取配置，實現與勿擾綁定的手錶省電狀態
        boolean wearPowerSave = sharedPreferences.getBoolean("wear_power_save_response", false);
        boolean wearVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true);

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            json.put("wearPowerSave", wearPowerSave);
            json.put("wearVibrate", wearVibrate);
            json.put("timestamp", System.currentTimeMillis());

            sendJsonMessage(json.toString());
            Log.d(TAG, "⚡ 成功向手錶發射同步封包: " + json.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to compile sync status packet", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String currentPackage = sbn.getPackageName();

        SharedPreferences sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        boolean alarmMasterSwitch = sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false);

        if (!alarmMasterSwitch) return;

        String allowedClockPackages = sharedPreferences.getString("custom_allowed_clock_packages", "com.google.android.deskclock,com.sec.android.app.clockpackage,com.android.deskclock");
        boolean isClockApp = false;
        if (allowedClockPackages != null) {
            for (String pkg : allowedClockPackages.split(",")) {
                if (currentPackage.trim().equalsIgnoreCase(pkg.trim())) {
                    isClockApp = true;
                    break;
                }
            }
        }

        if (isClockApp) {
            Notification notification = sbn.getNotification();
            if (notification == null) return;

            currentAlarmNotification = sbn;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "ringing");
                sendJsonMessage(json.toString());
                Log.d(TAG, "🔥 成功攔截並向手錶傳遞鬧鐘響鈴狀態！來自: " + currentPackage);
            } catch (Exception e) {
                Log.e(TAG, "發送鬧鐘狀態失敗", e);
            }
        }
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
                Log.d(TAG, "🔒 手機鬧鐘已關閉/暫停，已同步通知手錶終止響鈴。");
            } catch (Exception e) {}
        }
    }
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                if (!"wear".equals(sender)) return;

                String type = json.optString("type", "");
                
                // 處理來自手錶端的遠端相機指令控制
                if ("camera_control".equals(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 接收到手錶端反向控制指令: " + action);

                    SharedPreferences sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    boolean cameraMasterSwitch = sharedPreferences.getBoolean("custom_camera_sync_master_switch", false);
                    String allowedCameraPackages = sharedPreferences.getString("custom_allowed_camera_packages", "");

                    // 修正 3：如果手機端沒有開啟相機連動，或者包名為空且未開啟任何相機，則不執行任何操作
                    if (!cameraMasterSwitch) {
                        Log.w(TAG, "相機連動未在 UI 啟用，拒絕執行手錶控制信號。");
                        return;
                    }

                    if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                        // 執行拍照：向手機系統模擬發送音量鍵或相機物理快門按鍵事件，觸發拍照
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                                if (audioManager != null) {
                                    // 透過模擬硬體快門鍵，完美相容所有主流手機內建相機的拍照
                                    audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA));
                                    audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA));
                                    Log.d(TAG, "📸 已成功向手機系統注入快門按鍵事件！");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "模擬快門失敗", e);
                            }
                        });
                    } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                        Log.d(TAG, "收到手錶退出信號，關閉手機相機通道。");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "解析手錶反向回傳資料出錯", e);
            }
        }
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
            } catch (Exception e) {
                Log.e(TAG, "藍牙訊息通道傳輸失敗", e);
            }
        }).start();
    }
}
