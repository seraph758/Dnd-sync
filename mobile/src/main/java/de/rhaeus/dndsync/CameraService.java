package de.rhaeus.dndsync;

import android.annotation.SuppressLint;
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
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CHANNEL_ID = "wear_sync_camera_service_channel";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private boolean isCameraInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        cameraExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wear Camera Sync")
                .setContentText("后台相机预载抓取中...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(2026, notification);

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("START_CAMERA".equalsIgnoreCase(action)) {
                if (!isCameraInitialized) initCamera();
            } else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                executeSilentCapture();
            } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void initCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 🎯 极致流控：锁定 160x160 配合最新帧抛弃策略，确保蓝牙极速传输，长久绝不黑屏！
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(160, 160))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processYuvFramePacked);

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, imageCapture);
                isCameraInitialized = true;
                Log.d(TAG, "✅ CameraX 硬件后置图像绑定就绪");
            } catch (Exception e) {
                Log.e(TAG, "挂载本地相机流底座异常", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void processYuvFramePacked(@NonNull ImageProxy image) {
        try {
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                return;
            }

            // 🎯 高性能轻量 YUV 提取
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

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            // 极致画质 30% 压缩
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 30, out);
            byte[] rawBytes = out.toByteArray();

            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "camera_stream");
            json.put("base64Frame", android.util.Base64.encodeToString(rawBytes, android.util.Base64.DEFAULT));

            sendUniversalBytes(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "数据帧高度压缩编码失败", e);
        } finally {
            // 💥 铁律：必须无条件最快速度执行 image.close()，否则硬件底层一帧阻塞就会引发系统级黑屏！
            image.close();
        }
    }

    private void executeSilentCapture() {
        if (imageCapture == null) return;
        File outputDir = getExternalFilesDir(null);
        if (outputDir == null) outputDir = getFilesDir();
        File photoFile = new File(outputDir, "WearSync_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Log.d(TAG, "📸 远程静默拍摄图片成功，已安全存储在手机端: " + photoFile.getAbsolutePath());
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "远程捕获实体快门失败", exception);
            }
        });
    }

    private void sendUniversalBytes(byte[] data) {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {}
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Wear Sync Camera", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        isCameraInitialized = false;
        Log.d(TAG, "📸 CameraService 彻底释放完毕");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
