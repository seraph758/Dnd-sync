package de.rhaeus.dndsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearCameraActivity";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    private ImageView previewImage;
    private ImageButton btnCapture;
    
    // 用于接收分块图像数据
    private byte[] imageBuffer = new byte[0];
    private int expectedSize = 0;
    private int receivedSize = 0;
    private boolean isReceivingImage = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        previewImage = findViewById(R.id.preview_image);
        btnCapture = findViewById(R.id.btn_capture);
        
        // 📸 监听手表快门点击，向手机发送拍照信号
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "Capture button clicked on Wear, sending shutter command to phone...");
                sendActionToPhone("TAKE_PHOTO");
            });
        }
        
        // 注册消息接收器
        Wearable.getMessageClient(this).addListener(this);
        
        // 告知手机端，手表相机的 UI 画布已就位
        sendActionToPhone("START_CAMERA");
        Log.d(TAG, "WearCameraActivity 已启动");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        
        if (CAMERA_STREAM_PATH.equals(path)) {
            // 接收相机图像数据流
            handleCameraStreamData(messageEvent.getData());
        }
    }

    /**
     * 处理相机流数据
     * 格式：
     * - 起始帧：0xFF 标志 + 高字节 + 低字节（表示图像大小）
     * - 数据帧：JPEG 数据块
     */
    private void handleCameraStreamData(byte[] data) {
        if (data == null || data.length == 0) return;
        
        try {
            // 检查是否是起始信号
            if (data[0] == (byte) 0xFF && data.length >= 3) {
                // 新的图像帧开始
                int highByte = data[1] & 0xFF;
                int lowByte = data[2] & 0xFF;
                expectedSize = (highByte << 8) | lowByte;
                
                // 如果数据大小过大，使用扩展大小计算
                if (expectedSize == 0 && data.length >= 5) {
                    expectedSize = ((data[3] & 0xFF) << 24) | 
                                   ((data[4] & 0xFF) << 16) |
                                   ((data[1] & 0xFF) << 8) |
                                   (data[2] & 0xFF);
                }
                
                imageBuffer = new byte[Math.min(expectedSize, 1024 * 1024)]; // 最大1MB
                receivedSize = 0;
                isReceivingImage = true;
                Log.d(TAG, "新图像帧开始，预期大小: " + expectedSize + " bytes");
                return;
            }
            
            // 接收数据块
            if (isReceivingImage && receivedSize < imageBuffer.length) {
                int copyLen = Math.min(data.length, imageBuffer.length - receivedSize);
                System.arraycopy(data, 0, imageBuffer, receivedSize, copyLen);
                receivedSize += copyLen;
                
                if (receivedSize % (50 * 1024) == 0) {
                    Log.d(TAG, "已接收 " + receivedSize + "/" + expectedSize + " bytes");
                }
                
                // 检查是否接收完整
                if (receivedSize >= expectedSize && expectedSize > 0) {
                    displayReceivedImage();
                    isReceivingImage = false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing camera stream data", e);
        }
    }

    /**
     * 显示接收到的图像
     */
    private void displayReceivedImage() {
        try {
            if (receivedSize > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBuffer, 0, receivedSize);
                if (bitmap != null) {
                    previewImage.setImageBitmap(bitmap);
                    Log.d(TAG, "✅ 图像显示成功，大小: " + receivedSize + " bytes");
                } else {
                    Log.e(TAG, "❌ Bitmap 解码失败");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying image", e);
        }
    }

    /**
     * 向手机发送动作信号（JSON 格式）
     */
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
                    Log.d(TAG, "📤 发送动作到手机: " + actionName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send action to phone: " + actionName, e);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Wearable.getMessageClient(this).addListener(this);
        Log.d(TAG, "消息监听已启用");
    }

    @Override
    protected void onPause() {
        Wearable.getMessageClient(this).removeListener(this);
        Log.d(TAG, "消息监听已禁用");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sendActionToPhone("STOP_CAMERA");
        Log.d(TAG, "WearCameraActivity 已销毁");
        super.onDestroy();
    }
}
