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
    private ChannelClient.Channel mOpenedChannel = null;
    private volatile boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        btnCapture.setOnClickListener(v -> startCountdown());

        // 註冊 Message 控制監聽器
        Wearable.getMessageClient(this).addListener(this);

        // 註冊 Channel 接收通道監聽器
        setupChannelCallback();
        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);

        Log.d(TAG, "⌚ 手錶觀景窗就緒，發送 START_CAMERA 信號...");
        notifyPhoneCameraService("START_CAMERA");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String action = json.optString("action", "");

            if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                Log.w(TAG, "🛑 收到手機端主動關閉信號，滑順退出");
                mainHandler.post(this::finish);
            } else if ("TAKE_PICTURE_DONE".equalsIgnoreCase(action)) {
                mainHandler.post(() -> {
                    if (tvCountdown != null) tvCountdown.setText("✅ 已存檔");
                    mainHandler.postDelayed(() -> {
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                        if (btnCapture != null) btnCapture.setEnabled(true);
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
                    mOpenedChannel = channel;
                    isListening = true;
                    Log.d(TAG, "🤝 [/wear-camera-stream] 管道已捕獲，開始獲取輸入流...");
                    
                    // 異步啟動不阻塞的流解碼執行緒
                    new Thread(() -> readAndDecodeCameraStream(channel)).start();
                }
            }

            @Override
            public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                if (channel == mOpenedChannel) {
                    Log.d(TAG, "🔒 管道關閉");
                    isListening = false;
                    mOpenedChannel = null;
                    mainHandler.post(() -> finish());
                }
            }
        };
    }

    private void readAndDecodeCameraStream(ChannelClient.Channel channel) {
        try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {
            Log.d(TAG, "✅ 步驟 2：手錶端 InputStream 已完全就緒！立刻發回安全點火信號 WATCH_READY...");
            notifyPhoneCameraService("WATCH_READY");

            // 🎯 核心回歸：不再用大端序計算長度去拆包！直接利用 decodeStream 自動捕捉 JPEG 幀
            while (isListening) {
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (bitmap == null) {
                    // 如果流斷開或為空，安全跳出，絕不產生執行緒死鎖
                    break;
                }
                
                mainHandler.post(() -> {
                    if (frameView != null) frameView.setImageBitmap(bitmap);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "流式解碼影像異常", e);
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
        
        // 通知手機端迅速釋放相機
        notifyPhoneCameraService("STOP_CAMERA");
        
        if (mOpenedChannel != null) {
            try {
                Wearable.getChannelClient(this).close(mOpenedChannel);
            } catch (Exception ignored) {}
            mOpenedChannel = null;
        }
        super.onDestroy();
    }
}
