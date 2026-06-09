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
    
    // 🎯 修正三：去掉轉義多餘的反斜杠
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private ImageView frameView;
    private TextView tvCountdown;
    private Button btnCapture;
    private int countdown = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 🚀 長連接核心：異步執行緒與停止標誌
    private Thread mReceiveThread = null;
    private volatile boolean isListening = false;

    // 🎯 修正四：補全被不小心漏掉的完整的 onCreate 方法簽名
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 為了不依賴 DataBinding 的佈局問題，直接透過原生最安全的代碼獲取 UI
        setContentView(R.layout.activity_wear_camera);

        frameView = findViewById(R.id.frameView);
        tvCountdown = findViewById(R.id.tvCountdown);
        btnCapture = findViewById(R.id.btnCapture);

        // 通知手機端：啟動相機服務並做好接管長連接的準備
        notifyPhoneCameraService("START_CAMERA");

        // 啟動長連接自來水管異步監聽
        startChannelStreamListener();

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                btnCapture.setEnabled(false);
                startCountdown();
            });
        }
    }

    private void startChannelStreamListener() {
        isListening = true;
        ChannelClient channelClient = Wearable.getChannelClient(this);

        // 2. 建立唯一的靜態回調，不要在 while 循環裡重複 register
        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(ChannelClient.Channel channel) {
                if (!"/wear-camera-stream".equals(channel.getPath())) return;
                new Thread(() -> {
                    try {
                        InputStream is = Tasks.await(channelClient.getInputStream(channel));
                        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
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

        // 3. 在外部僅註冊一次
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
                    // 🎯 控制信令：走 MessageClient 發送拍照
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
        isListening = false; // 瞬間讓異步 read 退出循環
        
        // 4. 顯式註銷谷歌通道回調，徹底杜絕 Activity 銷毀後的殘留 FC
        if (mChannelCallback != null) {
            Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        }
        
        mainHandler.removeCallbacksAndMessages(null);
        notifyPhoneCameraService("STOP_CAMERA"); // 通知手機安全下線
        super.onDestroy();
    }
}
