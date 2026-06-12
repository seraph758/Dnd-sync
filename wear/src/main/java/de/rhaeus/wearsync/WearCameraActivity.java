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
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;
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
    private ChannelClient.Channel mOpenedChannel = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        btnCapture.setOnClickListener(v -> startCountdown());

        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                super.onChannelOpened(channel);
                if ("/wear-camera-stream".equals(channel.getPath())) {
                    mOpenedChannel = channel;
                    Log.d(TAG, "🚀 [/wear-camera-stream] 長連接數據管道已對齊，開始異步接收影格...");
                    isListening = true;
                    readStreamDataAsync(channel);

                    notifyPhoneCameraService("WATCH_READY");
                }
            }

            @Override
            public void onInputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                Log.w(TAG, "🛑 手機端已主動關閉相機或管道異常中斷。原因代碼: " + closeReason);
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        Log.d(TAG, "🏁 聯動退出手錶端觀景窗。");
                        finish();
                    }
                });
            }
        };

        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);

        Log.d(TAG, "⌚ 手錶端觀景窗就緒，發送 START_CAMERA 點火信號...");
        notifyPhoneCameraService("START_CAMERA");
    }

    private void readStreamDataAsync(ChannelClient.Channel channel) {
        new Thread(() -> {
            try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))) {
                byte[] headerBuffer = new byte[4];

                while (isListening) {
                    int bytesRead = 0;
                    while (bytesRead < 4) {
                        int read = is.read(headerBuffer, bytesRead, 4 - bytesRead);
                        if (read == -1) throw new Exception("流已斷開");
                        bytesRead += read;
                    }

                    int frameLength = ((headerBuffer[0] & 0xFF) << 24)
                                    | ((headerBuffer[1] & 0xFF) << 16)
                                    | ((headerBuffer[2] & 0xFF) << 8)
                                    | (headerBuffer[3] & 0xFF);

                    if (frameLength <= 0 || frameLength > 2048 * 1024) {
                        continue; 
                    }

                    byte[] jpegBuffer = new byte[frameLength];
                    int imgBytesRead = 0;
                    while (imgBytesRead < frameLength) {
                        int read = is.read(jpegBuffer, imgBytesRead, frameLength - imgBytesRead);
                        if (read == -1) throw new Exception("數據流在中途斷開");
                        imgBytesRead += read;
                    }

                    Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBuffer, 0, jpegBuffer.length);
                    if (bitmap != null) {
                        mainHandler.post(() -> {
                            if (frameView != null) frameView.setImageBitmap(bitmap);
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "🔒 傳輸通道關閉或讀取異常: " + e.getMessage());
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        Log.d(TAG, "🏁 管道已斷開，手錶觀景窗同步徹底退出關閉");
                        finish();
                    }
                });
            }
        }).start();
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
                    Log.d(TAG, "📤 [手錶發信] 指令投遞成功 -> " + action);
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
        mainHandler.removeCallbacksAndMessages(null);

        notifyPhoneCameraService("STOP_CAMERA"); 
        super.onDestroy();
    }
}
