package de.rhaeus.dndsync;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.Toast;
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
    protected void Bundle) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_camera);

        ImageButton btnCapture = findViewById(R.id.btn_capture);
        
        // 🎯 監聽手錶按鈕點擊
        btnCapture.setOnClickListener(v -> {
            Log.d(TAG, "Capture button clicked on Wear, sending shutter command to phone...");
            sendActionToPhone("TAKE_PHOTO");
        });
        
        // 通知手機端手錶已就位
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
