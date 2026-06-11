package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
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
        
        // 🎯 核心修正四：让手表 Activity 一经创建就强行点亮屏幕，并锁死屏幕常亮防止熄屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED 
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);    
        
        setContentView(R.layout.activity_wear_camera);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        // 🎯 核心修正一：补齐原本漏掉的方法调用！让手表能监听到手机发来的关闭指令
        setupMessageListener();

        // 通知手机端拉起背景相机服务
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

    // 🎯 核心補齊：監聽手機端發來的關閉指令
    private void setupMessageListener() {
        Wearable.getMessageClient(this).addListener((messageEvent) -> {
            if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
                try {
                    String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(jsonStr);
                    String action = json.optString("action", "");

                    if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                        Log.d(TAG, "🛑 收到手機端關閉相機信號，手錶 Activity 主動退出");
                        finish(); // 乾淨關閉手錶畫面
                    }
                } catch (Exception e) {
                    Log.e(TAG, "手錶監聽指令異常", e);
                }
            }
        });
    }

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
                        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
                        byte[] buffer = new byte[16384]; 
                        int readBytes;

                        while (isListening && (readBytes = is.read(buffer)) != -1) {
                            for (int i = 0; i < readBytes; i++) {
                                frameBuffer.write(buffer[i]);
                                int size = frameBuffer.size();
                                if (size > 4 && frameBuffer.toByteArray()[size - 2] == (byte) 0xFF 
                                        && frameBuffer.toByteArray()[size - 1] == (byte) 0xD9) {
                                    byte[] rawJpeg = frameBuffer.toByteArray();
                                    frameBuffer.reset();
                                    if (rawJpeg.length > 0) {
                                        Bitmap bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.length);
                                        if (bitmap != null) {
                                            mainHandler.post(() -> { if (frameView != null) frameView.setImageBitmap(bitmap); });
                                        }
                                    }
                                }
                            }
                        }
                        is.close();
                    } catch (Exception e) {
                        Log.d(TAG, "長管道讀取安全結束或中斷");
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
                    if (tvCountdown != null) {
                        tvCountdown.setText(String.valueOf(countdown));
                    }
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
