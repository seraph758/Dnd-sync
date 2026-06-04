package de.rhaeus.dndsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
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
import java.util.ArrayList;
import java.util.List;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearCameraActivity";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    
    private ImageView previewImage;
    private ImageButton btnCapture;
    
    // 用于接收分块图像数据
    private ByteArrayOutputStream imageBuffer;
    private int expectedChunks = 0;
    private int receivedChunks = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        previewImage = findViewById(R.id.preview_image);
        btnCapture = findViewById(R.id.btn_capture);
        
        imageBuffer = new ByteArrayOutputStream();
        
        // 🎯 监听手表快门点击，向通道异步下发拍照信号
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
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (CAMERA_STREAM_PATH.equals(messageEvent.getPath())) {
            // 接收相机图像数据流
            handleCameraStreamData(messageEvent.getData());
        } else if (UNIVERSAL_SYNC_PATH.equals(messageEvent.getPath())) {
            // 处理其他通用消息
            try {
                String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                String type = json.optString("type", "");
                
                if ("camera_stream_end".equals(type)) {
                    Log.d(TAG, "相机流结束，图像接收完成");
                    displayReceivedImage();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing camera message", e);
            }
        }
    }

    /**
     * 处理相机流数据
     * 格式：第一个字节表示分块总数，之后的字节为JPEG数据
     */
    private void handleCameraStreamData(byte[] data) {
        if (data == null || data.length < 1) return;
        
        try {
            // 如果是新的图像帧开始
            if (data[0] == -1) {  // 0xFF 标志新开始
                if (expectedChunks > 0 && receivedChunks == expectedChunks) {
                    // 上一张图显示完了，重置缓冲区
                    displayReceivedImage();
                }
                imageBuffer = new ByteArrayOutputStream();
                receivedChunks = 0;
                if (data.length > 2) {
                    expectedChunks = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                    Log.d(TAG, "新图像帧开始，总块数: " + expectedChunks);
                }
                return;
            }
            
            // 写入JPEG数据
            imageBuffer.write(data);
            receivedChunks++;
            
            if (receivedChunks % 10 == 0) {
                Log.d(TAG, "已接收 " + receivedChunks + "/" + expectedChunks + " 块");
            }
            
            // 检查是否接收完整
            if (expectedChunks > 0 && receivedChunks >= expectedChunks) {
                displayReceivedImage();
                expectedChunks = 0;
                receivedChunks = 0;
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
            byte[] imageData = imageBuffer.toByteArray();
            if (imageData.length > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                if (bitmap != null) {
                    previewImage.setImageBitmap(bitmap);
                    Log.d(TAG, "图像显示成功，大小: " + imageData.length + " bytes");
                } else {
                    Log.e(TAG, "Bitmap 解码失败");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying image", e);
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
        Wearable.getMessageClient(this).addListener(this);
    }

    @Override
    protected void onPause() {
        Wearable.getMessageClient(this).removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sendActionToPhone("STOP_CAMERA");
        super.onDestroy();
    }
}
