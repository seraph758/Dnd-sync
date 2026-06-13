package de.rhaeus.wearsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSync_WearCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView frameView;
    private TextView tvCountdown;
    private Button btnCapture;
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private ChannelClient.ChannelCallback mChannelCallback;
    private ChannelClient.Channel mActiveChannel = null;
    private volatile boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        btnCapture.setEnabled(false);
        btnCapture.setText("連線中...");
        btnCapture.setOnClickListener(v -> startCountdown());

        Wearable.getMessageClient(this).addListener(this);

        // 1. 註冊通道回調
        setupChannelCallback();
        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);

        // 2. 🎯 先發送 START_CAMERA 讓手機端 Listener 和 MainActivity 提權準備好
        Log.d(TAG, "⌚ 手錶端啟動，通知手機相機服務提權準備...");
        notifyPhoneCameraService("START_CAMERA");

        // 3. 🎯 核心修正：延時 300 毫秒，由手錶端主動發起通道建立，打破氧OS後台丟棄死結
        mainHandler.postDelayed(this::openStreamChannelFromWatch, 300);
    }

    private void openStreamChannelFromWatch() {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) {
                    Log.e(TAG, "❌ 未找到配對的手機節點");
                    return;
                }
                String nodeId = nodes.get(0).getId();
                Log.d(TAG, "🚀 手錶端主動向手機開通傳輸通道: /wear-camera-stream");
                // 這裡會觸發兩端的 onChannelOpened 回調
                Tasks.await(Wearable.getChannelClient(this).openChannel(nodeId, "/wear-camera-stream"));
            } catch (Exception e) {
                Log.e(TAG, "🚨 手錶主動建立通道失敗", e);
            }
        }).start();
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String action = json.optString("action", "");

            if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                mainHandler.post(this::finish);
            } else if ("TAKE_PICTURE_DONE".equalsIgnoreCase(action)) {
                mainHandler.post(() -> {
                    if (tvCountdown != null) tvCountdown.setText("✅ 已存檔");
                    mainHandler.postDelayed(() -> {
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                        if (btnCapture != null) {
                            btnCapture.setEnabled(true);
                            btnCapture.setText("拍照");
                        }
                    }, 1000);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "解析控制信號失敗", e);
        }
    }

    private void setupChannelCallback() {
        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                if ("/wear-camera-stream".equals(channel.getPath())) {
                    Log.d(TAG, "⚡ 通道已雙向打通，準備讀取影像流...");
                    mActiveChannel = channel;
                    isListening = true;
                    new Thread(() -> readAndDecodeCameraStream(channel)).start();
                }
            }

            @Override
            public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                if (channel == mActiveChannel) {
                    isListening = false;
                    mActiveChannel = null;
                    mainHandler.post(() -> finish());
                }
            }
        };
    }

    private void readAndDecodeCameraStream(ChannelClient.Channel channel) {
        try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {
            // 通道一開，立刻通知手機端：手錶準備好了，手機可以噴射 CameraX 數據流了！
            notifyPhoneCameraService("WATCH_READY");

            byte[] headerBuffer = new byte[4];
            
            while (isListening) {
                int headerBytesRead = 0;
                while (headerBytesRead < 4 && isListening) {
                    int read = is.read(headerBuffer, headerBytesRead, 4 - headerBytesRead);
                    if (read == -1) { isListening = false; break; }
                    headerBytesRead += read;
                }
                if (!isListening) break;

                int frameLength = ((headerBuffer[0] & 0xFF) << 24)
                                | ((headerBuffer[1] & 0xFF) << 16)
                                | ((headerBuffer[2] & 0xFF) << 8)
                                | (headerBuffer[3] & 0xFF);

                if (frameLength <= 0 || frameLength > 2 * 1024 * 1024) continue;

                byte[] imgBytes = new byte[frameLength];
                int imgBytesRead = 0;
                while (imgBytesRead < frameLength && isListening) {
                    int read = is.read(imgBytes, imgBytesRead, frameLength - imgBytesRead);
                    if (read == -1) { isListening = false; break; }
                    imgBytesRead += read;
                }
                if (!isListening) break;

                Bitmap bitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bitmap != null) {
                    mainHandler.post(() -> {
                        if (btnCapture != null && !btnCapture.isEnabled() && "連線中...".equals(btnCapture.getText())) {
                            btnCapture.setEnabled(true);
                            btnCapture.setText("拍照");
                        }
                        if (frameView != null) frameView.setImageBitmap(bitmap);
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "影像流拆包解碼異常", e);
        }
    }

    private void startCountdown() {
        btnCapture.setEnabled(false);
        countdown = 3;
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.setText(String.valueOf(countdown));

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
                Log.e(TAG, "手錶通訊失敗: " + action, e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        isListening = false;
        Wearable.getMessageClient(this).removeListener(this);
        if (mChannelCallback != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        }
        mainHandler.removeCallbacksAndMessages(null);
        notifyPhoneCameraService("STOP_CAMERA");
        if (mActiveChannel != null) {
            try { Wearable.getChannelClient(this).close(mActiveChannel); } catch (Exception ignored) {}
            mActiveChannel = null;
        }
        super.onDestroy();
    }
}
