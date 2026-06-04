package de.rhaeus.dndsync;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearCameraActivity";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    
    private ImageView previewImage;
    private ImageButton btnCapture;
    private PowerManager.WakeLock wakeLock = null;
    
    private ByteArrayOutputStream imageBuffer;
    private int expectedChunks = 0;
    private int receivedChunks = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 强行突破系统休眠与锁屏层
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
                "WearSync:CameraInteractiveWake"
            );
        }

        imageBuffer = new ByteArrayOutputStream();

        btnCapture.setOnClickListener(v -> {
            Log.d(TAG, "📸 Capture button clicked manually on watch UI!");
            sendActionToPhone("TAKE_PICTURE");
        });
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (CAMERA_STREAM_PATH.equals(messageEvent.getPath())) {
            try {
                byte[] data = messageEvent.getData();
                if (data == null || data.length < 4) return;

                int chunkIndex = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                int totalChunks = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

                if (chunkIndex == 0) {
                    imageBuffer.reset();
                    expectedChunks = totalChunks;
                    receivedChunks = 0;
                }

                imageBuffer.write(data, 4, data.length - 4);
                receivedChunks++;

                if (receivedChunks == expectedChunks) {
                    byte[] fullImageData = imageBuffer.toByteArray();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(fullImageData, 0, fullImageData.length);
                    if (bitmap != null) {
                        runOnUiThread(() -> previewImage.setImageBitmap(bitmap));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Stream decoding collapsed", e);
            }
        }
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
                Log.e(TAG, "Failed to callback action: " + actionName, e);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(15000); // 强行维持15秒最高焦点的图像刷新通道
        }
        // 重新请求强制将当前 Window 获取物理焦点
        getWindow().getDecorView().requestFocus();
        Wearable.getMessageClient(this).addListener(this);
    }

    @Override
    protected void onPause() {
        Wearable.getMessageClient(this).removeListener(this);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sendActionToPhone("STOP_CAMERA");
        super.onDestroy();
    }
}
