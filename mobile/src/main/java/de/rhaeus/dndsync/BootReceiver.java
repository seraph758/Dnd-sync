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
        String action = intent.getAction();
        Log.d(TAG, "收到开机广播: " + action);

        // 只处理需要的开机广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.d(TAG, "系统启动完成，12秒后执行重绑操作...");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                toggleNotificationListener(context);
            }, 12000); // 12秒延迟
        }
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
            Log.e(TAG, "重绑 NotificationListenerService 失败", e);
        }
    }
}
