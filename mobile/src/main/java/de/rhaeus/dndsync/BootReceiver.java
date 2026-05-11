package de.rhaeus.dndsync;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Boot completed received: " + intent.getAction());
        
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // 强制重绑 NotificationListenerService
            toggleNotificationListener(context);
        }, 15000); // 延迟15秒，避免系统启动太早
    }

    private void toggleNotificationListener(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, DNDNotificationService.class);
            
            pm.setComponentEnabledSetting(componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            
            pm.setComponentEnabledSetting(componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            
            Log.d(TAG, "NotificationListenerService 重绑完成");
        } catch (Exception e) {
            Log.e(TAG, "重绑失败", e);
        }
    }
}
