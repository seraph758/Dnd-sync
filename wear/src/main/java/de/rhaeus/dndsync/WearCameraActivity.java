package de.rhaeus.dndsync;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity {
    private static final String TAG = "WearCameraActivity";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    private ImageView previewImage;
    private ImageButton btnCapture;
    private PowerManager.WakeLock wakeLock = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 核心修复 3（解决黑屏）：在 setContentView 之前向系统强制注入全高亮、解锁、置顶视窗标志位
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_wear_camera);

        previewImage = findViewById(R.id.preview_image);
        btnCapture = findViewById(R.id.btn_capture);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                "WearSync:ForceCameraInteractive"
            );
        }

        // 核心修复 3（解决点击失效）：强制让整个按钮视图链条取得硬件物理焦点
        btnCapture.setFocusable(true);
        btnCapture.setFocusableInTouchMode(true);
        btnCapture.setOnClickListener(v -> {
            Log.d(TAG, "手錶端拍照按钮响应成功，正在向手机回传物理快门信号！");
            sendActionToPhone("TAKE_PICTURE");
        });
    }

    private void sendActionToPhone(String actionName) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionName);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "反向发送控制指令失败", e);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10000); 
        }
        // 二次强刷视图焦点，彻底消灭 viewVisibility=8 的系统级黑底降维现象
        getWindow().getDecorView().requestFocus();
    }

    @Override
    protected void onPause() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onPause();
    }
}
