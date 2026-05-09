package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(android.os.Looper.getMainLooper());

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            byte[] data = messageEvent.getData();
            if (data == null || data.length == 0) return;
            
            boolean phoneDndOn = (data[0] == 1); 
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            if (phoneDndOn != (mNotificationManager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_PRIORITY)) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    isInternalUpdate = true;

                    // 执行增强版组合拳
                    if (prefs.getBoolean("bedtime_key", true)) {
                        applySmartCombo(phoneDndOn, prefs);
                    }

                    mNotificationManager.setInterruptionFilter(phoneDndOn ? 
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);

                    handler.postDelayed(() -> isInternalUpdate = false, 2000);
                }
            }
        }
    }

    private void applySmartCombo(boolean dndActive, SharedPreferences prefs) {
        try {
            if (dndActive) {
                // 【进入勿扰】：强制全部关闭以进入睡眠模式
                Settings.Global.putInt(getContentResolver(), "zen_mode", 1);
                Settings.Secure.putInt(getContentResolver(), "wake_gesture_enabled", 0);
                Settings.Secure.putInt(getContentResolver(), "double_tap_to_wake", 0);
                Settings.System.putInt(getContentResolver(), "aod_mode", 0);
            } else {
                // 【退出勿扰】：根据 UI 选项决定是否恢复开启
                Settings.Global.putInt(getContentResolver(), "zen_mode", 0);
                
                if (prefs.getBoolean("restore_wake_key", true)) {
                    Settings.Secure.putInt(getContentResolver(), "wake_gesture_enabled", 1);
                }
                
                if (prefs.getBoolean("restore_touch_key", true)) {
                    Settings.Secure.putInt(getContentResolver(), "double_tap_to_wake", 1);
                }
                
                if (prefs.getBoolean("restore_aod_key", true)) {
                    Settings.System.putInt(getContentResolver(), "aod_mode", 1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "设置写入失败", e);
        }
    }
}
