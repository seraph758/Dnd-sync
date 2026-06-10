package de.rhaeus.wearsync;

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
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity {
    private ChannelClient.ChannelCallback mChannelCallback = null;
    private static final String TAG = "WearSync_WearCamera";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static final String ACTION_STOP_CAMERA_ACTIVITY = "de.rhaeus.wearsync.STOP_CAMERA_ACTIVITY";

    private ImageView frameView;
    private TextView tvCountdown;
    private Button btnCapture;
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isListening = false;

    // 🎯 本地廣播監聽器：接收後台 Service 傳來的關閉信號
    private final BroadcastReceiver mStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP_CAMERA_ACTIVITY.equals(intent.getAction())) {
                Log.d(TAG, "🛑 收到手機端按鈕下發的退出訊號，手錶 Activity 執行自我關閉");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> startCountdownAndCapture());
        }

        // 註冊關閉廣播
        registerReceiver(mStopReceiver, new IntentFilter(ACTION_STOP_CAMERA_ACTIVITY), Context.RECEIVER_NOT_EXPORTED);

        startChannelStreamListener();
    }

    private void startChannelStreamListener() {
        isListening = true;
        ChannelClient channelClient = Wearable.getChannelClient(this);

        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(ChannelClient.Channel channel) {
                if (!"/wear-camera-stream".equals(channel.getPath())) return;
                Log.d(TAG, "🟢 影像長管道已開啟，啟動精準長度流監聽協議器...");
                new Thread(() -> {
                    try {
                        InputStream is = Tasks.await(channelClient.getInputStream(channel));
                        byte[] headerBuffer = new byte[4];

                        while (isListening) {
                            // 1. 精準讀滿 4 個字節的長度信息報頭
                            int bytesReadHeader = 0;
                            while (bytesReadHeader < 4) {
                                int r = is.read(headerBuffer, bytesReadHeader, 4 - bytesReadHeader);
                                if (r == -1) throw new java.io.EOFException("長管道被手機端關閉");
                                bytesReadHeader += r;
                            }

                            int imageSize = ByteBuffer.wrap(headerBuffer).getInt();
                            if (imageSize <= 0 || imageSize > 1024 * 1024 * 5) continue;

                            // 2. 根據精準長度，建立快取並精準讀滿影像數據
                            byte[] imageBuffer = new byte[imageSize];
                            int bytesReadData = 0;
                            while (bytesReadData < imageSize) {
                                int r = is.read(imageBuffer, bytesReadData, imageSize - bytesReadData);
                                if (r == -1) throw new java.io.EOFException("影像數據流傳輸中斷");
                                bytesReadData += r;
                            }

                            // 3. 直接發送解碼，杜絕盲目特徵碼搜尋
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
                        Log.d(TAG, "長度協議管道監聽正常結束: " + e.getMessage());
                    }
                }).start();
            }
        };

        channelClient.registerChannelCallback(mChannelCallback);
    }

    private void startCountdownAndCapture() {
        if (btnCapture != null) btnCapture.setEnabled(false);
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
                Log.e(TAG, "手錶向手機發送相機指令失敗: " + action, e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        isListening = false;
        if (mChannelCallback != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        }
        try {
            unregisterReceiver(mStopReceiver);
        } catch (Exception ignored) {}
        mainHandler.removeCallbacksAndMessages(null);
        notifyPhoneCameraService("STOP_CAMERA");
        super.onDestroy();
    }
}
