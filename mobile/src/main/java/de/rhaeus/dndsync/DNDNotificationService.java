package de.rhaeus.dndsync;

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
    public static boolean running = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "手机端同步发射端服务挂载成功");
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
        // 核心修复 2：系统勿扰改变触发，isRealTimeSync 传入 true 允许手錶震动
        pushDndAndPowerStatusToWear(interruptionFilter, true);
    }

    public void pushDndAndPowerStatusToWear(int dndState, boolean isRealTimeSync) {
        SharedPreferences sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        boolean isDndSyncEnabled = sharedPreferences.getBoolean("dnd_sync_switch", true);
        
        if (!isDndSyncEnabled) return;

        boolean wearPowerSave = sharedPreferences.getBoolean("wear_power_save_response", false);
        boolean wearVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true);

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", dndState);
            json.put("wearPowerSave", wearPowerSave);
            json.put("wearVibrate", wearVibrate);
            json.put("isRealTimeSync", isRealTimeSync); 
            json.put("timestamp", System.currentTimeMillis());

            sendJsonMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "打包JSON失败", e);
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
                
                if ("camera_control".equals(type)) {
                    String action = json.optString("action", "");
                    SharedPreferences sharedPreferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    boolean cameraMasterSwitch = sharedPreferences.getBoolean("custom_camera_sync_master_switch", false);
                    String allowedCameraPackages = sharedPreferences.getString("custom_allowed_camera_packages", "").trim();

                    // 核心修复 3：没填包名或者关闭开关联动时，坚决拦截不拉起，也不注入快门事件
                    if (!cameraMasterSwitch || allowedCameraPackages.isEmpty()) {
                        Log.w(TAG, "拦截：相机联动已关闭或未填入任何包名！");
                        return;
                    }

                    if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                                if (audioManager != null) {
                                    audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA));
                                    audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA));
                                    Log.d(TAG, "📸 快门物理键注入成功");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "快门注入失败", e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "处理手錶反向回传失败", e);
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
                Log.e(TAG, "蓝牙发送失败", e);
            }
        }).start();
    }
}
