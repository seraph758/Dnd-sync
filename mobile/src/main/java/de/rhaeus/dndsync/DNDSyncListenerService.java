package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static boolean isInternalUpdate = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("phone".equalsIgnoreCase(sender)) return; // 杜绝自发自收

            // 1️⃣ 勿扰板块：手表端反向修改手机系统勿扰
            if ("dnd".equalsIgnoreCase(type)) {
                int dndValue = json.optInt("dnd_profile_value", -1);
                if (dndValue != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.getCurrentInterruptionFilter() != dndValue) {
                        Log.d(TAG, "🌙 收到手表反向勿扰信令 -> 开启连锁防死循环防护锁");
                        isInternalUpdate = true;
                        nm.setInterruptionFilter(dndValue);
                        // 2秒隔离，防止两端乒乓球死循环震动
                        mainHandler.postDelayed(() -> isInternalUpdate = false, 2000);
                    }
                }
                return;
            }

            // 2️⃣ 闹钟板块：手表端反向消除/延后手机闹钟
            if ("alarm_control".equalsIgnoreCase(type)) {
                Log.d(TAG, "⏰ 收到手表反向控制闹钟请求: " + action);
                SharedPreferences sp = getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE);
                if (!sp.getBoolean("alarm_master", true)) return;

                StatusBarNotification sbn = DNDNotificationService.currentAlarmNotification;
                if (sbn != null && sbn.getNotification() != null) {
                    String targetKeyword = "DISMISS".equalsIgnoreCase(action) ? 
                            sp.getString("alarm_stop", "停止") : sp.getString("alarm_snooze", "延后");

                    boolean clicked = false;
                    if (sbn.getNotification().actions != null) {
                        for (android.app.Notification.Action act : sbn.getNotification().actions) {
                            if (act.title != null && act.title.toString().contains(targetKeyword)) {
                                if (act.actionIntent != null) {
                                    act.actionIntent.send();
                                    clicked = true;
                                    Log.d(TAG, "⏰ 成功匹配到关键按钮 [" + act.title + "] 并实施代点");
                                    break;
                                }
                            }
                        }
                    }
                    // 顽固闹钟暴力防护
                    if (!clicked || "DISMISS".equalsIgnoreCase(action)) {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.cancel(sbn.getTag(), sbn.getId());
                        Log.d(TAG, "⏰ 代点机制未生效或执行清除，暴力抹除通知栏");
                    }
                }
                return;
            }

            // 3️⃣ 相机板块：手表端反向生命周期连动控制
            if ("camera_control".equalsIgnoreCase(type)) {
                Intent cameraIntent = new Intent(this, CameraService.class);
                cameraIntent.setAction(action);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(cameraIntent);
                } else {
                    startService(cameraIntent);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "手机端 MessageClient 协议接收并解析失败", e);
        }
    }
}
