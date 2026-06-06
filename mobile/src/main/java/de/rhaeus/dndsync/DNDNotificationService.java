package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.remote.RemoteCameraRegistry;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 讓 Service 實現 LifecycleOwner，這樣 CameraX 就能在無 UI 的後台順利綁定生命週期
public class DNDNotificationService extends NotificationListenerService implements MessageClient.OnMessageReceivedListener, LifecycleOwner {
    private static final String TAG = "WearSync_PhoneService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static StatusBarNotification currentAlarmNotification = null;
    public static boolean running = false;
    
    private ImageCapture imageCapture;
    private LifecycleRegistry lifecycleRegistry;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        running = true;
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "🚀 手機端接收解析服務掛載就緒");
    }

    // 當手錶要求開啟相機時，手機端默默在後台初始化相機硬體，不顯示任何手機畫面
    private void setupBackgroundCamera() {
        try {
            ProcessCameraProvider.getInstance(this).addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                    
                    // 僅配置拍照組件，完全不配置手機端 Preview，確保手機端沒有畫面，也不會產生硬體獨佔衝突
                    imageCapture = new ImageCapture.Builder().build();
                    CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                    // 綁定到服務生命週期
                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture);

                    // 向穿戴端核心註冊，此時底層流會自動對接到手錶端的 RemoteView
                    RemoteCameraRegistry.getInstance(this).registerCamera(cameraProvider, cameraSelector);
                    Log.d(TAG, "📸 CameraX 後台取景數據流已就緒，手機端無畫面隱蔽執行中");
                } catch (Exception e) {
                    Log.e(TAG, "後台相機綁定失敗", e);
                }
            }, getMainExecutor());
        } catch (Exception e) {
            Log.e(TAG, "獲取 CameraProvider 失敗", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        Wearable.getMessageClient(this).removeListener(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        running = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        super.onInterruptionFilterChanged(interruptionFilter);
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("dnd_sync_switch", true)) return;

        try {
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "dnd");
            json.put("dndValue", interruptionFilter);
            json.put("wearSleepModeLink", prefs.getBoolean("wear_sleep_mode_link", true));
            json.put("wearPowerSave", prefs.getBoolean("wear_power_save_link", false));
            json.put("vibrateTipsEnable", prefs.getBoolean("wear_vibrate_on_sync", true));
            json.put("timestamp", System.currentTimeMillis());
            sendJsonMessage(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "推送勿擾封包錯誤", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("custom_alarm_sync_master_switch", false)) return;

        String pkg = sbn.getPackageName();
        String allowedConfig = prefs.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock");
        
        boolean isPackageAllowed = false;
        String[] allowedPkgs = allowedConfig.split(",");
        for (String item : allowedPkgs) {
            if (pkg.equalsIgnoreCase(item.trim())) {
                isPackageAllowed = true;
                break;
            }
        }
        if (!isPackageAllowed) return;

        String eventType = prefs.getString("alarm_event_type_select", "ringing");
        Notification notification = sbn.getNotification();
        
        if ("ringing".equalsIgnoreCase(eventType)) {
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String titleStr = title != null ? title.toString() : "";
            String textStr = text != null ? text.toString() : "";
            if (titleStr.contains("預告") || textStr.contains("即將到期") || titleStr.contains("Upcoming")) {
                Log.d(TAG, "🛑 成功阻斷非標準預告鬧鐘的透傳");
                return; 
            }
        }

        if (Notification.CATEGORY_ALARM.equals(notification.category) || (notification.flags & Notification.FLAG_INSISTENT) != 0) {
            currentAlarmNotification = sbn;
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "alarm");
                json.put("alarmAction", "LAUNCH_WEAR_ALARM_ACTIVITY");
                sendJsonMessage(json.toString());
                Log.d(TAG, "🔥 觸發鬧鐘流硬聯鎖：向手錶發出同步拉起 WearAlarmActivity 廣播");
            } catch (Exception e) {
                Log.e(TAG, "透傳鬧鐘激活失敗", e);
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
                json.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                sendJsonMessage(json.toString());
            } catch (Exception e) {}
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                if ("phone".equalsIgnoreCase(json.optString("sender", ""))) return;

                String type = json.optString("type", "");
                
                // 当收到手表的拉起请求时，初始化后台不发光相机数据流
                if ("camera_control".equalsIgnoreCase(type) && "REQUEST_LAUNCH_CAMERA".equalsIgnoreCase(json.optString("action"))) {
                    setupBackgroundCamera();
                    return;
                }

                // 響應手錶端的快門拍照要求
                if ("camera_action".equalsIgnoreCase(type) && "TAKE_PICTURE".equalsIgnoreCase(json.optString("action"))) {
                    if (imageCapture != null) {
                        File file = new File(getExternalFilesDir(null), "WearSync_" + System.currentTimeMillis() + ".jpg");
                        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();
                        imageCapture.takePicture(options, getMainExecutor(), new ImageCapture.OnImageSavedCallback() {
                            @Override 
                            public void onImageSaved(@NonNull ImageCapture.OutputFileResults res) { 
                                Log.d(TAG, "📸 協同拍照成功！照片儲存至: " + file.getAbsolutePath()); 
                            }
                            @Override 
                            public void onError(@NonNull ImageCaptureException e) {
                                Log.e(TAG, "拍照失敗", e);
                            }
                        });
                    }
                    return;
                }

                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 收到手錶傳回的精準控制要求: " + action);
                    
                    if (currentAlarmNotification != null) {
                        Notification.Action[] actions = currentAlarmNotification.getNotification().actions;
                        if (actions != null && actions.length > 0) {
                            SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                            
                            String rule = "關鍵字智能匹配";
                            if ("DISMISS".equalsIgnoreCase(action)) {
                                rule = prefs.getString("custom_dismiss_action_index", "關鍵字智能匹配");
                            } else {
                                rule = prefs.getString("custom_snooze_action_index", "關鍵字智能匹配");
                            }

                            boolean executed = false;

                            if (rule.contains("第 1 個")) {
                                if (actions.length >= 1) { actions[0].actionIntent.send(); executed = true; }
                            } else if (rule.contains("第 2 個")) {
                                if (actions.length >= 2) { actions[1].actionIntent.send(); executed = true; }
                            } else if (rule.contains("第 3 個")) {
                                if (actions.length >= 3) { actions[2].actionIntent.send(); executed = true; }
                            } else if ("自定義輸入關鍵字".equals(rule)) {
                                String userKeyword = "DISMISS".equalsIgnoreCase(action) ? 
                                        prefs.getString("custom_dismiss_keyword_input", "") : prefs.getString("custom_snooze_keyword_input", "");
                                if (userKeyword != null && !userKeyword.trim().isEmpty()) {
                                    for (Notification.Action act : actions) {
                                        String title = act.title.toString().toLowerCase();
                                        if (title.contains(userKeyword.trim().toLowerCase())) {
                                            act.actionIntent.send(); executed = true; break;
                                        }
                                    }
                                }
                            }

                            if (!executed && (rule.equals("關鍵字智能匹配") || "自定義輸入關鍵字".equals(rule))) {
                                for (Notification.Action act : actions) {
                                    String title = act.title.toString().toLowerCase();
                                    if ("DISMISS".equalsIgnoreCase(action)) {
                                        if (title.contains("停") || title.contains("關") || title.contains("消") || title.contains("結") || title.contains("dis")) {
                                            act.actionIntent.send(); executed = true; break;
                                        }
                                    } else {
                                        if (title.contains("延") || title.contains("稍") || title.contains("後") || title.contains("再") || title.contains("snoo")) {
                                            act.actionIntent.send(); executed = true; break;
                                        }
                                    }
                                }
                            }

                            if (!executed) {
                                actions[0].actionIntent.send();
                            }
                        }
                    }
                    
                    try {
                        JSONObject exitJson = new JSONObject();
                        exitJson.put("sender", "phone");
                        exitJson.put("type", "alarm");
                        exitJson.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                        sendJsonMessage(exitJson.toString());
                    } catch (Exception e) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "處理控制流異常", e);
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
                Log.e(TAG, "藍牙發送失敗", e);
            }
        }).start();
    }
}
