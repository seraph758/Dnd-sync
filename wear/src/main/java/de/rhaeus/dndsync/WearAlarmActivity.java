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
                Log.d(TAG, "收到外界通知层解除信号 -> 连带退出App界面");
                cleanUpAndFinish();
            }
        }
    };

    private final Runnable vibrationRunnable = new Runnable() {
        @Override
        public void run() {
            if (isVibrating && activityVibrator != null) {
                activityVibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
                vibrationHandler.postDelayed(this, 800);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 强行锁屏亮屏弹起
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        setContentView(R.layout.activity_wear_alarm); // 确保手表端具备该布局

        Button btnDismiss = findViewById(R.id.btn_wear_dismiss);
        Button btnSnooze = findViewById(R.id.btn_wear_snooze);

        activityVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        vibrationHandler = new Handler(Looper.getMainLooper());
        isVibrating = true;
        vibrationHandler.post(vibrationRunnable);

        registerReceiver(stopReceiver, new IntentFilter("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"));

        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                sendControlActionToPhone("DISMISS");
                cleanUpAndFinish(); // 👈 点击后立即自我彻底销毁任务树
            });
        }
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                sendControlActionToPhone("SNOOZE");
                cleanUpAndFinish(); // 👈 点击后立即自我彻底销毁任务树
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

    private void cleanUpAndFinish() {
        isVibrating = false;
        if (vibrationHandler != null) vibrationHandler.removeCallbacks(vibrationRunnable);
        if (activityVibrator != null) activityVibrator.cancel();
        try { unregisterReceiver(stopReceiver); } catch (Exception e) {}
        finishAndRemoveTask(); // 干净撤离，绝不驻留任何影子进程
    }

    @Override
    protected void onDestroy() {
        cleanUpAndFinish();
        super.onDestroy();
    }
}
