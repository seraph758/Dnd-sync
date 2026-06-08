package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
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
    private static final String TAG = "WearSync_WearCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView frameView;
    private TextView tvCountdown;
    private Button btnCapture;
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 🚀 核心大升級：接管由 WearSyncListenerService 異步讀取 Channel 後拋出的原始本地位元組廣播
    private final BroadcastReceiver cameraStreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "de.rhaeus.wearsync.LOCAL_WEAR_CAMERA_STREAM".equals(intent.getAction())) {
                byte[] jpeg = intent.getByteArrayExtra("WEAR_JPEG");
                if (jpeg != null && jpeg.length > 0) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        if (frameView != null && bitmap != null) {
                            runOnUiThread(() -> frameView.setImageBitmap(bitmap)); // 絲滑渲染畫面
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解碼本地 Channel 圖片位元組流異常", e);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("activity_wear_camera", "layout", getPackageName()));

        frameView = findViewById(getResources().getIdentifier("camera_frame_view", "id", getPackageName()));
        tvCountdown = findViewById(getResources().getIdentifier("tv_countdown", "id", getPackageName()));
        btnCapture = findViewById(getResources().getIdentifier("btn_capture", "id", getPackageName()));

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> startCountdown());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 動態註冊高性能本地圖片廣播監聽器
        IntentFilter filter = new IntentFilter("de.rhaeus.wearsync.LOCAL_WEAR_CAMERA_STREAM");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraStreamReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(cameraStreamReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        // 註銷廣播，防止記憶體洩漏
        unregisterReceiver(cameraStreamReceiver);
        super.onPause();
    }

    private void startCountdown() {
        countdown = 3;
        if (tvCountdown != null) {
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText(String.valueOf(countdown));
        }
        if (btnCapture != null) btnCapture.setEnabled(false);

        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (countdown > 1) {
                    countdown--;
                    if (tvCountdown != null) tvCountdown.setText(String.valueOf(countdown));
                    mainHandler.postDelayed(this, 1000);
                } else {
                    if (tvCountdown != null) tvCountdown.setText("📸");
                    // 🎯 倒計時完美歸零，正式通知手機端 CameraX 捕獲並保存高清相片
                    notifyPhoneCameraService("TAKE_PICTURE");
                    mainHandler.postDelayed(() -> {
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                        if (btnCapture != null) btnCapture.setEnabled(true);
                    }, 800);
                }
            }
        };
        mainHandler.postDelayed(r, 1000);
    }

    private void notifyPhoneCameraService(String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", action);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "手表向手机发送相机指令失败: " + action, e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        notifyPhoneCameraService("STOP_CAMERA");
        super.onDestroy();
    }
}
