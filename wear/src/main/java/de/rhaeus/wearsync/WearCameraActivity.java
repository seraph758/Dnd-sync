package de.rhaeus.wearsync;

import android.app.Activity;
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
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity {
    private ChannelClient.ChannelCallback mChannelCallback = null;
    private static final String TAG = "WearSync_WearCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView frameView;
    private TextView tvCountdown;
    private Button btnCapture;
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        // 🎯 完美對齊：通知手機端拉起背景相機服務
        notifyPhoneCameraService("START_CAMERA");

        // 啟動 Channel 監聽
        startChannelStreamListener();

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                btnCapture.setEnabled(false);
                startCountdown();
            });
        }
    }

// 🎯 升級手錶端的監聽解包協議，徹底消滅黑屏
        private void startChannelStreamListener() {
            isListening = true;
            ChannelClient channelClient = Wearable.getChannelClient(this);
        
            mChannelCallback = new ChannelClient.ChannelCallback() {
                @Override
                public void onChannelOpened(ChannelClient.Channel channel) {
                    if (!"/wear-camera-stream".equals(channel.getPath())) return;
                    new Thread(() -> {
                        try {
                            InputStream is = Tasks.await(channelClient.getInputStream(channel));
                            byte[] headerBuffer = new byte[4]; // 專門讀取 4 字節長度的快取
        
                            while (isListening) {
                                // 1. 精準讀取 4 字節的長度頭
                                int bytesReadHeader = 0;
                                while (bytesReadHeader < 4) {
                                    int r = is.read(headerBuffer, bytesReadHeader, 4 - bytesReadHeader);
                                    if (r == -1) throw new java.io.EOFException("管道過早關閉");
                                    bytesReadHeader += r;
                                }
        
                                // 2. 解析出即將進來的圖片大小
                                int imageSize = java.nio.ByteBuffer.wrap(headerBuffer).getInt();
                                if (imageSize <= 0 || imageSize > 5 * 1024 * 1024) continue; // 安全邊界保護
        
                                // 3. 根據長度，精準開闢並讀滿圖片字節
                                byte[] imageBuffer = new byte[imageSize];
                                int bytesReadData = 0;
                                while (bytesReadData < imageSize) {
                                    int r = is.read(imageBuffer, bytesReadData, imageSize - bytesReadData);
                                    if (r == -1) throw new java.io.EOFException("圖片傳輸中斷");
                                    bytesReadData += r;
                                }
        
                                // 4. 高效解碼並直接刷新到 UI 畫面
                                if (isListening) {
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBuffer, 0, imageBuffer.length);
                                    if (bitmap != null) {
                                        mainHandler.post(() -> {
                                            if (frameView != null) frameView.setImageBitmap(bitmap);
                                        });
                                    }
                                }
                            }
                            is.close();
                        } catch (Exception e) {
                            Log.d(TAG, "長管道讀取安全結束、手錶退出或連接中斷: " + e.getMessage());
                        }
                    }).start();
                }
            };
        
            channelClient.registerChannelCallback(mChannelCallback);
        }
        
        
        
    private void startCountdown() {
        countdown = 3;
        if (tvCountdown != null) {
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText(String.valueOf(countdown));
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                countdown--;
                if (countdown > 0) {
                    if (tvCountdown != null) tvCountdown.setText(String.valueOf(countdown));
                    mainHandler.postDelayed(this, 1000);
                } else {
                    if (tvCountdown != null) tvCountdown.setText("📸");
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
        isListening = false; 
        if (mChannelCallback != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        }
        mainHandler.removeCallbacksAndMessages(null);
        notifyPhoneCameraService("STOP_CAMERA"); 
        super.onDestroy();
    }
}
