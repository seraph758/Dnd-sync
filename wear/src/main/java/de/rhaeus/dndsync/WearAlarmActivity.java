package de.rhaeus.dndsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearAlarmActivity extends Activity {
    private static final String TAG = "WearSync_AlarmActivity";
    private Vibrator activityVibrator;
    private boolean isVibrating = false;
    private Handler vibrationHandler;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI".equals(intent.getAction())) {
                Log.d(TAG, "收到自毀信號，強制連帶退出手錶App介面");
                cleanUpAndFinishWithHardKill();
            }
        }
    };

    private final Runnable vibrationRunnable = new Runnable() {
        @Override
        public void run() {
            if (isVibrating && activityVibrator != null) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        long[] timings = new long[]{0, 600, 300, 600};
                        int[] amplitudes = new int[]{0, 255, 0, 255};
                        activityVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
                    } else {
                        activityVibrator.vibrate(1000);
                    }
                } catch (Exception e) {}
                if (vibrationHandler != null) {
                    vibrationHandler.postDelayed(this, 1800);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                             WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_wear_alarm);

        vibrationHandler = new Handler(Looper.getMainLooper());
        activityVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        isVibrating = true;
        vibrationHandler.post(vibrationRunnable);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, new IntentFilter("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stopReceiver, new IntentFilter("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"));
        }

        Button btnDismiss = findViewById(R.id.btn_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                sendControlActionToPhone("DISMISS");
                cleanUpAndFinishWithHardKill(); // 🎯 核心修正：連帶徹底自殺式退出
            });
        }

        Button btnSnooze = findViewById(R.id.btn_snooze);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                sendControlActionToPhone("SNOOZE");
                cleanUpAndFinishWithHardKill(); // 🎯 核心修正：連帶徹底自殺式退出
            });
        }
    }

    private void sendControlActionToPhone(String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_control");
                json.put("action", action);
                json.put("timestamp", System.currentTimeMillis());
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception e) {}
        }).start();
    }

    /**
     * 🎯 核心修正：乾淨清除震動硬體、取消廣播，並強制終止進程防止殘留
     */
    private void cleanUpAndFinishWithHardKill() {
        isVibrating = false;
        if (vibrationHandler != null) {
            vibrationHandler.removeCallbacks(vibrationRunnable);
        }
        if (activityVibrator != null) {
            activityVibrator.cancel();
        }
        try {
            unregisterReceiver(stopReceiver);
        } catch (Exception e) {}
        
        // 銷毀任務棧
        finishAndRemoveTask();
        
        // 核心強殺：直接從作業系統內核抹除當前進程，杜絕界面殘留
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    @Override
    protected void onDestroy() {
        cleanUpAndFinishWithHardKill();
        super.onDestroy();
    }
}
