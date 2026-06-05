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
    
    // 🎯【解決手機端編譯報錯】宣告靜態變量，用於緩存當前正在響鈴的鬧鐘快照
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        // 註冊藍牙穿戴訊息監聽，接收來自手錶端的控制信號
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 穿戴互聯手機端發射服務已成功掛載！");
    }

    @Override
    public void onListenerDisconnected() {
        Wearable.getMessageClient(this).removeListener(this);
        running = false;
        super.onListenerDisconnected();
    }

    // 🎯 核心攔截：當手機彈出新通知時觸發
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String currentPackage = sbn.getPackageName();

        // 判斷是否為常見的手機內建鬧鐘（相容 Google 鬧鐘、三星、時鐘、OPPO/vivo等主流時鐘包名）
        if (currentPackage.contains("android.deskclock") || 
            currentPackage.contains("com.sec.android.app.clockpackage") || 
            currentPackage.contains("com.google.android.deskclock") ||
            currentPackage.contains("clock")) {
            
            // 判斷通知類別是否為鬧鐘類別，或者通知標籤/標題包含鬧鐘特徵
            Notification notification = sbn.getNotification();
            boolean isAlarmCategory = false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
                isAlarmCategory = Notification.CATEGORY_ALARM.equals(notification.category);
            }

            if (isAlarmCategory || (notification.flags & Notification.FLAG_INSISTENT) != 0) {
                // 🔥 將當前鬧鐘對象緩存到靜態變量中，供 DNDSyncListenerService 進行手錶反向控制時使用
                currentAlarmNotification = sbn;

                try {
                    // 組裝 JSON 封包，通知手錶「手機正在響鈴」
                    JSONObject json = new JSONObject();
                    json.put("sender", "phone");
                    json.put("type", "alarm");
                    json.put("alarmAction", "ringing");
                    json.put("timestamp", System.currentTimeMillis());
                    
                    sendJsonMessage(json.toString());
                    Log.d(TAG, "🔥 成功攔截手機鬧鐘！正在向手錶同步發射【響鈴】信號。包名: " + currentPackage);
                } catch (Exception e) {
                    Log.e(TAG, "發送鬧鐘響鈴狀態到手錶失敗", e);
                }
            }
        }
    }

    // 🎯 核心攔截：當手機通知消失（用戶在手機上滑掉、關閉、或小睡）時觸發
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        
        // 如果消失的通知正好是我們緩存的鬧鐘
        if (currentAlarmNotification != null && sbn.getKey().equals(currentAlarmNotification.getKey())) {
            currentAlarmNotification = null; // 清空緩存
            
            try {
                // 組裝 JSON 封包，通知手錶「手機鬧鐘已關閉/小睡」
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "stopped");
                json.put("timestamp", System.currentTimeMillis());
                
                sendJsonMessage(json.toString());
                Log.d(TAG, "🛑 手機鬧鐘已在手機端消除，已同步通知手錶停止震動。");
            } catch (Exception e) {
                Log.e(TAG, "發送鬧鐘關閉狀態到手錶失敗", e);
            }
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                if ("phone".equalsIgnoreCase(sender)) return; // 忽略手機端自身發送的

                String type = json.optString("type", "");
                
                // 處理相機快門控制指令
                if ("camera_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
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

    public void pushDndAndPowerStatusToWear(int dndState, boolean isRealTimeSync) {
        try {
            SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
            boolean wearPowerSave = prefs.getBoolean("sleep_mode_sync_switch", true);
            boolean wearVibrate = prefs.getBoolean("vibrate_on_sync_switch", true);

            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            json.put("wearPowerSave", wearPowerSave);
            json.put("wearVibrate", wearVibrate);
            json.put("isRealTimeSync", isRealTimeSync);
            json.put("timestamp", System.currentTimeMillis());

            sendJsonMessage(json.toString());
            Log.d(TAG, "⚡ 成功向手錶發射勿擾同步封包: " + json.toString());
        } catch (Exception e) {
            Log.e(TAG, "構建勿擾同步封包失敗", e);
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
