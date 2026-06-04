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

        // 1. 強行介入 Window 狀態，擊穿系統休眠，避免啟動時黑屏
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

        // 2. 初始化電源鎖
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                "WearSync:CameraWakeLockActive"
            );
        }

        imageBuffer = new ByteArrayOutputStream();

        // 3. 拍照按鈕點擊事件
        btnCapture.setOnClickListener(v -> {
            sendActionToPhone("TAKE_PICTURE");
        });
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        try {
            if (CAMERA_STREAM_PATH.equals(messageEvent.getPath())) {
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
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing incoming stream chunk", e);
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
                Log.d(TAG, "发送动作到手机: " + actionName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send action to phone: " + actionName, e);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 4. 獲取前台互動焦點時立即點亮螢幕
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10000); // 獲取最高權限亮屏維持 10 秒
        }
        Wearable.getMessageClient(this).addListener(this);
    }

    @Override
    protected void onPause() {
        Wearable.getMessageClient(this).removeListener(this);
        // 5. 釋放鎖，防止手錶異常耗電
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
