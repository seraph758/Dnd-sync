package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";

    // --- 新增：冷却时间控制，防止同步回环 ---
    private static long lastExecutionTime = 0;
    private static final long COOLDOWN_MS = 5000; // 5秒内不再重复执行相同逻辑
    // ------------------------------------

    @Override
    public void onMessageReceived (@NonNull MessageEvent messageEvent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onMessageReceived: " + messageEvent);
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH);

            // --- 核心逻辑：冷却时间校验 ---
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecutionTime < COOLDOWN_MS) {
                Log.d(TAG, "忽略：处于冷却时间内，可能由于系统回调或重复消息引起。");
                return;
            }
            // ---------------------------

            boolean vibrate = prefs.getBoolean("vibrate_key", false);
            Log.d(TAG, "vibrate: " + vibrate);
            if (vibrate) {
                vibrate();
            }

            byte[] data = messageEvent.getData();
            // data[0] contains dnd mode of phone
            // 0 = INTERRUPTION_FILTER_UNKNOWN
            // 1 = INTERRUPTION_FILTER_ALL (all notifications pass)
            // 2 = INTERRUPTION_FILTER_PRIORITY
            // 3 = INTERRUPTION_FILTER_NONE (no notification passes)
            // 4 = INTERRUPTION_FILTER_ALARMS
            byte dndStatePhone = data[0];
            Log.d(TAG, "dndStatePhone: " + dndStatePhone);

            // get dnd state
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            int filterState = mNotificationManager.getCurrentInterruptionFilter();
            if (filterState < 0 || filterState > 4) {
                Log.d(TAG, "DNDSync weird current dnd state: " + filterState);
            }
            byte currentDndState = (byte) filterState;
            Log.d(TAG, "currentDndState: " + currentDndState);

            if (dndStatePhone != currentDndState) {
                // --- 更新最后执行时间，锁定操作 ---
                lastExecutionTime = currentTime;
                // -----------------------------

                Log.d(TAG, "dndStatePhone != currentDndState: " + dndStatePhone + " != " + currentDndState);
                boolean useBedtimeMode = prefs.getBoolean("bedtime_key", true);
                Log.d(TAG, "useBedtimeMode: " + useBedtimeMode);
                
                if (useBedtimeMode) {
                    toggleBedtimeMode();
                }
                
                // set DND anyways, also in case bedtime toggle does not work to have at least DND
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    mNotificationManager.setInterruptionFilter(dndStatePhone);
                    Log.d(TAG, "DND set to " + dndStatePhone);
                } else {
                    Log.d(TAG, "attempting to set DND but access not granted");
                }
            }

        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    private void toggleBedtimeMode() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.d(TAG, "accessibility not connected");
            Handler mHandler = new Handler(getMainLooper());
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.acc_not_connected), Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        Log.d(TAG, "accessibility connected. Perform toggle.");
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:MyWakeLock");
        wakeLock.acquire(2*60*1000L /*2 minutes*/);

        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), getResources().getString(R.string.bedtime_toggle), Toast.LENGTH_SHORT).show();
            }
        });

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        serv.swipeDown();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        serv.clickIcon1_2();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        serv.goBack();
        wakeLock.release();
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
