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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.io.DataInputStream;

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

 // 🎯 修正後的 ChannelCallback 區塊
        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                super.onChannelOpened(channel);
                if ("/wear-camera-stream".equals(channel.getPath())) {
                    mOpenedChannel = channel;
                    Log.d(TAG, "🚀 [/wear-camera-stream] 長連接數據管道已對齊，開始異步接收影格...");
                    readStreamDataAsync(channel);
                    
                    // 管道建立成功，向手機回發 READY 握手信號
                    notifyPhoneCameraService("WATCH_READY");
                }
            }
        
            // 🎯 精準修復編譯報錯：補齊為 3 個參數，並移除 super 呼叫以相容編譯環境
            @Override
            public void onInputClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                Log.w(TAG, "🛑 手機端已主動關閉相機或管道異常中斷，原因代碼: " + closeReason + ", 錯誤碼: " + appSpecificErrorCode);
                
                // 聯動體驗優化：當手機端關閉時，手錶端觀景窗立刻自動 finish() 退出
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        Log.d(TAG, "🏁 聯動退出手錶端觀景窗。");
                        finish();
                    }
                });
            }
        };
        

        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);
        isListening = true;
        
        Log.d(TAG, "⌚ 手錶端觀景窗就緒，發送 START_CAMERA 點火信號...");
        notifyPhoneCameraService("START_CAMERA");
    }

    private void readStreamDataAsync(ChannelClient.Channel channel) {
    new Thread(() -> {
        // 封裝成 DataInputStream，利用 readFully 強制對齊字節
        try (DataInputStream dis = new DataInputStream(Tasks.await(Wearable.getChannelClient(this).getInputStream(channel)))) {
            
            while (isListening) {
                // 1. 強制讀滿 4 個字節的長度頭，少一個字節都會阻塞等待，絕不跑偏
                int frameLength = dis.readInt();
                if (frameLength <= 0 || frameLength > 2048 * 1024) continue; 

                // 2. 強制讀滿指定長度的 JPEG 數據
                byte[] jpegBuffer = new byte[frameLength];
                dis.readFully(jpegBuffer); // 👈 核心修復：不讀滿絕不返回

                // 3. 投遞到主線程 UI
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBuffer, 0, jpegBuffer.length);
                if (bitmap != null) {
                    mainHandler.post(() -> {
                        if (frameView != null) frameView.setImageBitmap(bitmap);
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "🔒 傳輸通道關閉或讀取異常: " + e.getMessage());
            mainHandler.post(this::finish); // 異常時安全退出
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
                    
                    // 倒數結束，精準向手機端發送遠程拍照信號
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
        
        // 如果手錶端 Activity 是自己滑動返回或主動點關閉銷毀的，通知手機端卸載相機硬體
        notifyPhoneCameraService("STOP_CAMERA"); 
        super.onDestroy();
    }
}
