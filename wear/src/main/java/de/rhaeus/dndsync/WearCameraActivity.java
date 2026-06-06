package de.rhaeus.dndsync;

import android.os.Bundle;
import android.widget.Button;
import androidx.activity.ComponentActivity;
import androidx.camera.remote.RemoteCameraClient;
import androidx.camera.remote.RemoteCameraControl;
import androidx.camera.remote.RemoteView;
import androidx.wear.widget.BoxInsetLayout;

public class WearCameraActivity extends ComponentActivity {

    private RemoteCameraClient remoteCameraClient;
    private RemoteCameraControl remoteCameraControl = null;
    private RemoteView remoteView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BoxInsetLayout layout = new BoxInsetLayout(this);
        setContentView(layout);

        // 遠端預覽
        remoteView = new RemoteView(this);
        layout.addView(remoteView);

        // 獲取 RemoteCameraClient
        remoteCameraClient = new RemoteCameraClient(this);

        // 綁定 RemoteCamera 流到 RemoteView
        remoteCameraClient.bindPreview(remoteView, control -> {
            remoteCameraControl = control;
        });

        // 拍照按鈕
        Button button = new Button(this);
        button.setText("拍照");
        button.setOnClickListener(v -> {
            if (remoteCameraControl != null) {
                remoteCameraControl.takePicture(success -> {
                    if (success) {
                        cleanUpAndFinish();
                    }
                });
            }
        });
        layout.addView(button);
    }

    private void cleanUpAndFinish() {
        try {
            if (remoteCameraClient != null) {
                remoteCameraClient.close();
            }
        } catch (Exception e) {
            // 忽略關閉異常
        }
        finishAndRemoveTask(); // 乾淨利落銷毀任務樹，徹底回退
    }

    @Override
    public void onBackPressed() {
        cleanUpAndFinish();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cleanUpAndFinish();
        super.onDestroy();
    }
}
