package de.rhaeus.dndsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler; // 👈 已补齐
import android.os.Looper;  // 👈 已补齐
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.View;
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
    private Handler vibrationHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI".equals(intent.getAction())) {
                Log.d(TAG, "收到强制关闭广播，正在退出 UI");
                cleanUpAndFinish();
            }
        }
    };

    // 🎯 持续循环高强度闹钟震动节拍
    private final Runnable vibrationRunnable = new Runnable() {
        @Override
        public void run() {
            if (isVibrating && activityVibrator != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    long[] timings = new long[]{0, 600, 300, 600};
                    int[] amplitudes = new int[]{0, 255, 0, 255};
                    activityVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
                } else {
                    activityVibrator.vibrate(1000);
                }
                vibrationHandler.postDelayed(this, 1800); // 持续循环产生节拍
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🚀 解锁屏幕、点亮屏幕、保持常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                             WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_wear_alarm);
        
        activityVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        isVibrating = true;
        vibrationHandler.post(vibrationRunnable); // 唤起持续震动
        
        registerReceiver(stopReceiver, new IntentFilter("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"));

        // 🎯 停止按钮点击事件（明确标记为 DISMISS）
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                sendControlActionToPhone("DISMISS");
                cleanUpAndFinish();
            });
        }

        // 🎯 延后按钮点击事件（明确标记为 SNOOZE）
        Button btnSnooze = findViewById(R.id.btn_snooze);
        if (btnSnooze != null) {
            btnSnooze.setOnClickListener(v -> {
                sendControlActionToPhone("SNOOZE");
                cleanUpAndFinish();
            });
        }
    }

    private void sendControlActionToPhone(String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_control");
                json.put("action", action); // 精准告诉手机是 DISMISS 还是 SNOOZE
                json.put("timestamp", System.currentTimeMillis());
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "🚀 精准发送动作到手机端: " + action);
            } catch (Exception e) {
                Log.e(TAG, "回传控制数据包出错", e);
            }
        }).start();
    }

    private void cleanUpAndFinish() {
        isVibrating = false;
        vibrationHandler.removeCallbacks(vibrationRunnable);
        if (activityVibrator != null) {
            activityVibrator.cancel();
        }
        try {
            unregisterReceiver(stopReceiver);
        } catch (Exception e) {}
        finish();
    }

    @Override
    protected void onDestroy() {
        cleanUpAndFinish();
        super.onDestroy();
    }
}
