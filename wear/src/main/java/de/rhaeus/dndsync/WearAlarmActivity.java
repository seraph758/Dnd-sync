package de.rhaeus.dndsync;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearAlarmActivity extends Activity {
    private LinearLayout containerLayout;
    private ValueAnimator colorAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🔒 強行破鎖屏：允許在鎖屏頂部顯示，點亮屏幕
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);

        setContentView(R.layout.activity_wear_alarm);

        containerLayout = findViewById(R.id.alarm_container);
        TextView txtTitle = findViewById(R.id.alarm_title);
        Button btnSnooze = findViewById(R.id.btn_snooze);
        Button btnDismiss = findViewById(R.id.btn_dismiss);

        // 🎬 3️⃣ & 4️⃣：構建全屏流光呼吸動畫（紅橙交替警戒）
        colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), 0xFF3E0000, 0xFF8B0000, 0xFF3E0000);
        colorAnimation.setDuration(1500);
        colorAnimation.setRepeatCount(ValueAnimator.INFINITE);
        colorAnimation.addUpdateListener(animator -> 
            containerLayout.setBackgroundColor((int) animator.getAnimatedValue())
        );
        colorAnimation.start();

        // 暫停按鈕事件（Snooze）
        btnSnooze.setOnClickListener(v -> {
            sendActionToPhone("snooze");
            finishAndDismiss();
        });

        // 關閉按鈕事件（Dismiss）
        btnDismiss.setOnClickListener(v -> {
            sendActionToPhone("dismiss");
            finishAndDismiss();
        });
    }

    private void sendActionToPhone(String actionType) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "alarm_control");
                json.put("action", actionType); // "dismiss" 或 "snooze"
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), "/wear-universal-sync", data);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void finishAndDismiss() {
        if (colorAnimation != null) colorAnimation.cancel();
        DNDSyncListenerService.stopLoopVibration(); // 停止手錶震動
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (colorAnimation != null) colorAnimation.cancel();
    }
}
