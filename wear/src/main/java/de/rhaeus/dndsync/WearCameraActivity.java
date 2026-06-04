package de.rhaeus.dndsync;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearCameraActivity extends Activity {
    private static final String TAG = "WearCameraActivity";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        ImageButton btnCapture = findViewById(R.id.btn_capture);
        
        // 🎯 监听手表快门点击，向通道异步下发拍照信号
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "Capture button clicked on Wear, sending shutter command to phone...");
                sendActionToPhone("TAKE_PHOTO");
            });
        }
        
        // 告知手机端，手表相机的 UI 画布已就位
        sendActionToPhone("START_CAMERA");
    }

    private void sendActionToPhone(String actionName) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_control");
                json.put("action", actionName);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send action to phone: " + actionName, e);
            }
        }).start();
    }
}
