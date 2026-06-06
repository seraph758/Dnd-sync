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
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    private static final String CHANNEL_ID = "wear_sync_camera_service_channel";

    private LifecycleRegistry lifecycleRegistry;
    private ImageCapture imageCapture;
    private boolean isCameraInitialized = false;
    private ExecutorService cameraExecutor;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        cameraExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wear Sync 協同相機")
                .setContentText("正在隱蔽與手錶進行跨端影像串流...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        startForeground(1005, notification);

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        if (!isCameraInitialized) {
            setupCameraXBackgroundPipeline();
        }

        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                executeCapturePhoto();
            } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                Log.d(TAG, "📥 收到手錶關閉相機指令 -> 自我銷毀釋放硬體");
                stopSelf();
            }
        }

        return START_NOT_STICKY;
    }

    private void setupCameraXBackgroundPipeline() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 🎯 核心修正：優化解析度為正方形適合手錶（320x320），策略設為只留最新幀防止畫面堆積黑屏
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    private long lastFrameTime = 0;
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastFrameTime < 250) { // 限流控制：每秒約 4 幀，降低藍牙頻寬載荷
                            image.close();
                            return;
                        }
                        lastFrameTime = currentTime;
                        
                        // 🎯 核心修正：將原RGBA轉Bitmap改為高效率的內存直接轉JPEG位元組
                        if (image.getFormat() == ImageFormat.YUV_420_888) {
                            byte[] jpegData = yuv420ToJpeg(image);
                            if (jpegData != null) {
                                sendFrameToWearDirectly(jpegData);
                            }
                        }
                        image.close();
                    }
                });

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, imageCapture);

                isCameraInitialized = true;
                Log.d(TAG, "📸 CameraX 背景取景與快門管線綁定成功！手機端不發光執行中");

            } catch (Exception e) {
                Log.e(TAG, "初始化 CameraX 失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * 🎯 高效率 YUV_420_888 內存幀直接壓縮為 JPEG 避免 OOM 與黑屏
     */
    private byte[] yuv420ToJpeg(ImageProxy image) {
        try {
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
            // 壓縮率設為 55%，極大減小體積，保證單包不超限，不需要進行不穩定的分包拆包
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 55, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "YUV 轉 JPEG 異常", e);
            return null;
        }
    }

    private void sendFrameToWearDirectly(byte[] rawBytes) {
        try {
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
            for (Node node : nodes) {
                // 直接透過主信道或子路徑安全發射單個高壓縮率幀
                Wearable.getMessageClient(this).sendMessage(node.getId(), CAMERA_STREAM_PATH, rawBytes);
            }
        } catch (Exception e) {
            Log.e(TAG, "傳輸影像幀失敗", e);
        }
    }

    private void executeCapturePhoto() {
        if (imageCapture == null) {
            Log.e(TAG, "❌ 快門元件尚未就緒，無法拍照");
            return;
        }
        File file = new File(getExternalFilesDir(null), "WearSync_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();

        Log.d(TAG, "📸 正在觸發實體相機快門...");
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Log.d(TAG, "✅ 協同拍照成功！照片已無損儲存至: " + file.getAbsolutePath());
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "❌ 跨端快門拍攝失敗", exception);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Wear Sync Camera Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        isCameraInitialized = false;
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        Log.d(TAG, "📸 CameraService 服務關閉，相機實體硬體已安全釋放");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
