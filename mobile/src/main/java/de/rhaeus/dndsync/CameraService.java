package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
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
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    private static final String CHANNEL_ID = "wear_sync_camera_service_channel";

    private LifecycleRegistry lifecycleRegistry;
    private ImageCapture imageCapture;
    private boolean isCameraInitialized = false;

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
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 為了在 Android 8.0+ 保持穩定後台執行，拉起常駐通知
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

        // 🎯 核心通訊校正：響應從 ListenerService 流轉分發過來的快門拍照或關閉控制指令
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

                // 1. 初始化即時幀數據分析元件（分塊傳輸手錶取景器）
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new ImageAnalysis.Analyzer() {
                    private long lastFrameTime = 0;
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastFrameTime < 200) { // 限流控制：每秒約 5 幀，兼顧流暢與功耗
                            image.close();
                            return;
                        }
                        lastFrameTime = currentTime;
                        sendFrameToWear(image);
                    }
                });

                // 2. 初始化實體快門拍照元件
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

    private void sendFrameToWear(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            // 轉成 Android 標準壓縮格式位元組
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            
            // 縮放取景圖，大幅降低藍牙傳輸載荷
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true);
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out);
            byte[] rawBytes = out.toByteArray();

            // 分塊傳輸核心邏輯
            new Thread(() -> {
                try {
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    for (Node node : nodes) {
                        int maxChunk = 40000; // 安全分包邊界
                        if (rawBytes.length <= maxChunk) {
                            Wearable.getMessageClient(this).sendMessage(node.getId(), CAMERA_STREAM_PATH, rawBytes);
                        } else {
                            int total = rawBytes.length;
                            int offset = 0;
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

            bitmap.recycle();
            scaled.recycle();
        } catch (Exception e) {
            Log.e(TAG, "幀封裝與投遞失敗", e);
        } finally {
            image.close();
        }
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
        Log.d(TAG, "📸 CameraService 服務關閉，相機實體硬體已安全釋放");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
