package de.rhaeus.dndsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TAG = "WearSync_WearMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 🎯 手表端新增：主动拉起并唤醒双端相机入口按钮绑定
        Button btnTriggerCamera = findViewById(R.id.btn_trigger_camera);
        if (btnTriggerCamera != null) {
            btnTriggerCamera.setOnClickListener(v -> triggerRemotePhoneCamera());
        }
    }

    private void triggerRemotePhoneCamera() {
        new Thread(() -> {
            try {
                // 1. 发送给手机，让手机拉起采集流
                JSONObject json = new JSONObject();
                json.put("sender", "wear");
                json.put("type", "camera_action");
                json.put("action", "START_CAMERA_UI");
                json.put("timestamp", System.currentTimeMillis());
                
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                java.util.List<com.google.android.gms.wearable.Node> nodes = 
                        com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getNodeClient(this).getConnectedNodes());
                for (com.google.android.gms.wearable.Node n : nodes) {
                    com.google.android.gms.wearable.Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }

                // 2. 本地立即打开手表端自己的 WearCameraActivity
                Intent intent = new Intent(this, WearCameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d(TAG, "📤 手表端成功触发反向唤醒相机逻辑并拉起本地UI");
            } catch (Exception e) {
                Log.e(TAG, "手表端主动唤醒相机失败", e);
            }
        }).start();
    }
}
