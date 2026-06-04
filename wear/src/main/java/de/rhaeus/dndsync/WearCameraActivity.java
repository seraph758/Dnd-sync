package de.rhaeus.dndsync;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

public class WearCameraActivity extends Activity implements MessageClient.OnMessageReceivedListener {
    private ImageView previewImage;
    private static final String CAMERA_PATH = "/camera-preview";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.xml.activity_wear_camera); // 自定義手錶佈局
        
        previewImage = findViewById(R.id.preview_image);
        ImageButton btnCapture = findViewById(R.id.btn_capture);

        // 監聽拍照按鈕
        btnCapture.setOnClickListener(v -> {
            // 向手機發送拍照指令
            sendControlToPhone("TAKE_PHOTO");
        });

        // 註冊藍牙管道監聽
        Wearable.getMessageClient(this).addListener(this);
        
        // 進入視窗時，通知手機：「手錶已就位，請手機打開相機開始推流」
        sendControlToPhone("START_CAMERA");
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (CAMERA_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] rawData = messageEvent.getData();
            // 收到極致壓縮的圖片位元組，還原為 Bitmap 並直接刷到螢幕上
            Bitmap bitmap = BitmapFactory.decodeByteArray(rawData, 0, rawData.length);
            runOnUiThread(() -> previewImage.setImageBitmap(bitmap));
        }
    }

    private void sendControlToPhone(String action) {
        new Thread(() -> {
            // 發送控制 JSON，通知手機端執行對應動作
            try {
                String payload = "{\"sender\":\"wear\",\"type\":\"camera_control\",\"action\":\"" + action + "\"}";
                // 複用您的萬能同步路徑發送，或者走獨立路徑
                Wearable.getMessageClient(this).sendMessage(phoneNodeId, "/wear-universal-sync", payload.getBytes());
            } catch (Exception e) {}
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 關閉視窗時，通知手機關閉相機，省電
        sendControlToPhone("STOP_CAMERA");
        Wearable.getMessageClient(this).removeListener(this);
    }
}
