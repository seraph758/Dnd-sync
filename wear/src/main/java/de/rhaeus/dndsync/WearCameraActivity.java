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
        
        // 动态选择布局：优先查找全功能相机布局，若没有则回退到系统简易视图
        int layoutId = getResources().getIdentifier("activity_wear_camera", "layout", getPackageName());
        if (layoutId != 0) {
            setContentView(layoutId);
        } else {
            setContentView(android.R.layout.activity_list_item);
        }

        // 🎯 动态获取组件 ID，如果找不到（返回0），则给予安全容错，防止编译与运行崩溃
        int imgViewId = getResources().getIdentifier("camera_frame_view", "id", getPackageName());
        int countId = getResources().getIdentifier("tv_wear_countdown", "id", getPackageName());
        int capBtnId = getResources().getIdentifier("btn_wear_capture", "id", getPackageName());
        int closeBtnId = getResources().getIdentifier("btn_wear_camera_close", "id", getPackageName());

        if (imgViewId != 0) frameImageView = findViewById(imgViewId);
        if (countId != 0) tvCountdown = findViewById(countId);
        
        if (capBtnId != 0) {
            btnCapture = findViewById(capBtnId);
            btnCapture.setOnClickListener(v -> triggerThreeSecondShootMacro());
        }
        
        if (closeBtnId != 0) {
            Button btnClose = findViewById(closeBtnId);
            if (btnClose != null) btnClose.setOnClickListener(v -> finish());
        }

        isActivityActive = true;
        registerReceiver(frameReceiver, new IntentFilter("de.rhaeus.dndsync.CAMERA_FRAME_RECEIVED"));

        // 启动远程相机静默服务
        sendActionToPhone("START_CAMERA");
    }

    private void triggerThreeSecondShootMacro() {
        if (btnCapture != null) btnCapture.setEnabled(false);
        countdownSeconds = 3;
        if (tvCountdown != null) {
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText(String.valueOf(countdownSeconds));
        }

        Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                countdownSeconds--;
                if (countdownSeconds > 0) {
                    if (tvCountdown != null) tvCountdown.setText(String.valueOf(countdownSeconds));
                    mainHandler.postDelayed(this, 1000);
                } else {
                    if (tvCountdown != null) tvCountdown.setText("📸");
                    sendActionToPhone("TAKE_PICTURE");
                    
                    mainHandler.postDelayed(() -> {
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                        if (btnCapture != null) btnCapture.setEnabled(true);
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
                Log.e(TAG, "发送相机信号异常", e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        isActivityActive = false;
        try { unregisterReceiver(frameReceiver); } catch (Exception e) {}
        mainHandler.removeCallbacksAndMessages(null);
        sendActionToPhone("STOP_CAMERA");
        super.onDestroy();
    }
}
