package de.rhaeus.dndsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity {
    private static final String TAG = "WearSync_CameraActivity";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    public static boolean isActivityActive = false;
    private ImageView frameImageView;
    private TextView tvCountdown;
    private Button btnCapture;
    
    private int countdownSeconds = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver frameReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.dndsync.CAMERA_FRAME_RECEIVED".equals(intent.getAction())) {
                byte[] rawJpeg = intent.getByteArrayExtra("raw_jpeg");
                if (rawJpeg != null && frameImageView != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.length);
                    if (bitmap != null) {
                        frameImageView.setImageBitmap(bitmap);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera); // 确保 xml 中包含此三个组件ID

        frameImageView = findViewById(R.id.camera_frame_view);
        tvCountdown = findViewById(R.id.tv_wear_countdown);
        btnCapture = findViewById(R.id.btn_wear_capture);
        Button btnClose = findViewById(R.id.btn_wear_camera_close);

        isActivityActive = true;

        registerReceiver(frameReceiver, new IntentFilter("de.rhaeus.dndsync.CAMERA_FRAME_RECEIVED"));

        // 手表一进入前景，立刻向手机端申请开启静默抓取
        sendActionToPhone("START_CAMERA");

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> triggerThreeSecondShootMacro());
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }
    }

    // 🎯 核心逻辑：3 2 1 本地倒计时完毕后再向手机端发射实体拍照指令
    private void triggerThreeSecondShootMacro() {
        btnCapture.setEnabled(false);
        countdownSeconds = 3;
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.setText(String.valueOf(countdownSeconds));

        Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                countdownSeconds--;
                if (countdownSeconds > 0) {
                    tvCountdown.setText(String.valueOf(countdownSeconds));
                    mainHandler.postDelayed(this, 1000);
                } else {
                    tvCountdown.setText("📸");
                    // 倒计时归零，发射
                    sendActionToPhone("TAKE_PICTURE");
                    
                    mainHandler.postDelayed(() -> {
                        tvCountdown.setVisibility(View.GONE);
                        btnCapture.setEnabled(true);
                    }, 800);
                }
            }
        };
        mainHandler.postDelayed(countdownRunnable, 1000);
    }

    private void sendActionToPhone(String actionName) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionName);
                json.put("timestamp", System.currentTimeMillis());
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "发送相机控制信号失败", e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        isActivityActive = false;
        try { unregisterReceiver(frameReceiver); } catch (Exception e) {}
        mainHandler.removeCallbacksAndMessages(null);
        // 手表退出，命令手机端彻底关闭 CameraService，安全解禁硬件锁
        sendActionToPhone("STOP_CAMERA");
        super.onDestroy();
    }
}
