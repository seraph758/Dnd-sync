package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    private static final String CHANNEL_ID = "wear_sync_camera_service_channel";

    private LifecycleRegistry lifecycleRegistry;
    private boolean isCameraInitialized = false;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 🎯 核心修复：正确进行生命周期的显式初始化，防止 CameraX 闪退
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wear Sync Camera Link")
                .setContentText("正在为穿戴端传输低延迟实时画面...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1024, notification);

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        initializeCamera();
    }

    private void initializeCamera() {
        if (isCameraInitialized) return;
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageFrame);
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                // 🎯 此时传递 this 具有合法的 Lifecycle 状态，绝不崩溃
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                isCameraInitialized = true;
                Log.d(TAG, "📸 CameraX 本地硬件层成功初始化绑定");
            } catch (Exception e) {
                Log.e(TAG, "CameraX 绑定核心链路失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageFrame(@NonNull ImageProxy image) {
        try {
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                image.close();
                return;
            }
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 50, out);
            byte[] rawBytes = out.toByteArray();

            new Thread(() -> {
                try {
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    for (Node node : nodes) {
                        if (rawBytes.length <= 40000) {
                            Wearable.getMessageClient(this).sendMessage(node.getId(), CAMERA_STREAM_PATH, rawBytes);
                        } else {
                            int offset = 0;
                            int total = rawBytes.length;
                            int maxChunk = 40000;
                            while (offset < total) {
                                int length = Math.min(maxChunk, total - offset);
                                byte[] chunk = new byte[length];
                                System.arraycopy(rawBytes, offset, chunk, 0, length);
                                Wearable.getMessageClient(this).sendMessage(node.getId(), CAMERA_STREAM_PATH + "/chunk", chunk);
                                offset += length;
                            }
                        }
                    }
                } catch (Exception e) {}
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "画面帧序列投递失败", e);
        } finally {
            image.close();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Wear Sync Camera Link Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (lifecycleRegistry != null) {
            lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        }
        isCameraInitialized = false;
        Log.d(TAG, "📸 手机相机底层硬体已被安全关闭并解绑释放");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
