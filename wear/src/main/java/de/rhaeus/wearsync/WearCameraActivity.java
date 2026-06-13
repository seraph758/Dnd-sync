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
import java.io.DataInputStream;
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
        
        // 🎯 點擊拍照按鈕執行倒數
        btnCapture.setOnClickListener(v -> startCountdown());

        // 🎯 如果畫面有設計「關閉」按鈕，可以綁定此點擊事件直接整體退出
        // findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        Wearable.getMessageClient(this).addListener(this);

        setupChannelCallback();
        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);

        Log.d(TAG, "⌚ 手錶端啟動，通知手機相機服務提權準備...");
        notifyPhoneCameraService("START_CAMERA");

        // 延時 300 毫秒由手錶端主動開闢通道
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
                // 🎯 收到停止信號，整體調用 finish() 退出
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
        try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
             DataInputStream dis = new DataInputStream(is)) { // 🎯 用 DataInputStream 進行鐵壁包裝
             
            // 🎯 [通行證協議核心]：手錶端此時 Input 管道和介面已完全就緒，正式發放通行證通知手機點火相機！
            Log.d(TAG, "🎫 手錶端就緒，向手機發送通行證：WATCH_READY");
            notifyPhoneCameraService("WATCH_READY");

            while (isListening) {
                // 🎯 使用 readFully 嚴格死等 4 字節頭部，指針絕對不發生錯位
                int frameLength = dis.readInt();

                if (frameLength <= 0 || frameLength > 2 * 1024 * 1024) continue;

                byte[] imgBytes = new byte[frameLength];
                // 🎯 使用 readFully 嚴格死等指定長度的圖片字節，徹底解決半包、錯位黑屏問題
                dis.readFully(imgBytes);

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
            Log.e(TAG, "影像流拆包解碼異常或通道正常關閉", e);
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
        
        // 🎯 退出時通知手機關閉相機
        notifyPhoneCameraService("STOP_CAMERA");
        
        if (mActiveChannel != null) {
            try { Wearable.getChannelClient(this).close(mActiveChannel); } catch (Exception ignored) {}
            mActiveChannel = null;
        }
        super.onDestroy();
    }
}
