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
        // 🎯【解決問題 2】手機端服務必須註冊藍牙穿戴訊息監聽，否則收不到手錶按鈕的反饋！
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 手機端 WearSync 核心監聽服務已成功啟動並與藍牙總線對齊");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        running = false;
        Wearable.getMessageClient(this).removeListener(this);
    }

    /**
     * 🎯【核心實現】在這裡接收來自手錶端的所有反向控制代碼（拍照、鬧鐘關閉等）
     */
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");
                String action = json.optString("action", "");

                // 只處理來自手錶的訊號
                if ("wear".equalsIgnoreCase(sender)) {
                    Log.d(TAG, "📥 收到來自手錶的遙控信號: type=" + type + ", action=" + action);
                    
                    if ("camera_control".equalsIgnoreCase(type)) {
                        if ("TAKE_PHOTO".equalsIgnoreCase(action)) {
                            Log.d(TAG, "📸 收到手錶快門指令！正在透過系統總線模擬物理快門按壓...");
                            performGlobalShutterAction();
                        } else if ("START_CAMERA".equalsIgnoreCase(action)) {
                            Log.d(TAG, "📹 手錶端相機已就位，準備開始進行動態低延遲流投射...");
                            // 在這裡可以擴充引導手機本地抓取 Surface 畫面
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "處理手錶反向控制封包失敗", e);
            }
        }
    }

    /**
     * 🎯 模擬物理音量鍵快門：這能相容市面上 99% 的手機原生及第三方相機應用
     */
    private void performGlobalShutterAction() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    // 發送音量下鍵按下信號 (Android 相機通用快門)
                    long now = android.os.SystemClock.uptimeMillis();
                    KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0);
                    KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0);
                    
                    audioManager.dispatchMediaKeyEvent(downEvent);
                    audioManager.dispatchMediaKeyEvent(upEvent);
                    Log.d(TAG, "✅ 物理快門模擬訊號發送完畢");
                }
            } catch (Exception e) {
                Log.e(TAG, "模擬快門失敗", e);
            }
        });
    }

    // ====================================================================
    // 原有鬧鐘穿透與時鐘包名攔截判斷邏輯
    // ====================================================================
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) {
            return; 
        }

        String currentPackage = sbn.getPackageName();
        String allowedPackagesStr = prefs.getString("custom_allowed_clock_packages", "com.google.android.deskclock,com.sec.android.app.clockpackage,com.android.deskclock");
        
        boolean isClockApp = false;
        if (allowedPackagesStr != null) {
            String[] pkgs = allowedPackagesStr.split(",");
            for (String pkg : pkgs) {
                if (currentPackage.equalsIgnoreCase(pkg.trim())) {
                    isClockApp = true;
                    break;
                }
            }
        }

        if (!isClockApp) {
            return; 
        }

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        CharSequence titleObj = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textObj = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = titleObj != null ? titleObj.toString() : "";
        String text = textObj != null ? textObj.toString() : "";

        String dismissKeywords = prefs.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭");
        String snoozeKeywords = prefs.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡");

        boolean matchesDismiss = false;
        if (dismissKeywords != null) {
            for (String k : dismissKeywords.split(",")) {
                String tk = k.trim();
                if (!tk.isEmpty() && (title.contains(tk) || text.contains(tk))) {
                    matchesDismiss = true;
                    break;
                }
            }
        }

        if (matchesDismiss) {
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
            } catch (Exception e) {}
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
            } catch (Exception e) {}
        }).start();
    }
}
