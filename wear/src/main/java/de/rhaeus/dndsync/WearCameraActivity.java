package de.rhaeus.dndsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearCameraActivity";
    private ImageView previewImage;
    private static final String CAMERA_PREVIEW_PATH = "/camera-preview";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 🎯 【修復錯誤3】路徑從 R.xml 改為 R.layout
        setContentView(R.layout.activity_wear_camera); 
        
        // 🎯 【修復錯誤4 & 5】有了上面的 XML，這裡就能順利找到組件了
        previewImage = findViewById(R.id.preview_image);
        ImageButton btnCapture = findViewById(R.id.btn_capture);

        // 監聽手錶端點擊拍照
        btnCapture.setOnClickListener(v -> sendControlToPhone("TAKE_PHOTO"));

        // 註冊藍牙穿戴訊息接收監聽器
        Wearable.getMessageClient(this).addListener(this);
        
        // 剛打開畫布時，通知手機端：手錶已就位，請開啟相機並推流
        sendControlToPhone("START_CAMERA");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        // 接收來自手機的高強度壓縮相機字節流
        if (CAMERA_PREVIEW_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] rawData = messageEvent.getData();
            if (rawData != null) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(rawData, 0, rawData.length);
                runOnUiThread(() -> previewImage.setImageBitmap(bitmap));
            }
        }
    }

    /**
     * 🎯 【修復錯誤6】動態獲取手機 Node 節點並發送反向控制 JSON 指令
     */
    private void sendControlToPhone(String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", action);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                // 動態尋找當前連線的手機節點
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "發送相機控制指令失敗: " + action, e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 當用戶退出手錶相機介面時，通知手機及時關閉相機，防止手機發熱耗電
        sendControlToPhone("STOP_CAMERA");
        Wearable.getMessageClient(this).removeListener(this);
    }
}
