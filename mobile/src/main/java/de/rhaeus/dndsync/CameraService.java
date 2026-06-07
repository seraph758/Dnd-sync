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
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CHANNEL_ID = "wear_sync_camera_channel";
    private static final int NOTIFICATION_ID = 2048; // 🎯 定義統一的通知 ID

    private LifecycleRegistry lifecycleRegistry;
    private ProcessCameraProvider cameraProvider;
    private boolean isRunning = false;

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        // 1️⃣ 第一步：優先建立通知管道（Android O 及以上必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Camera Stream", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        // 2️⃣ 第二步：構建標準的前台服務通知物件
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("远程相机采集器")
                .setContentText("正在低延迟向手表投递流画面...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        // 3️⃣ 第三步：核心修復！僅呼叫一次 startForeground，並在 Android Q 及以上精確註冊 CAMERA 類型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_CAMERA".equalsIgnoreCase(intent.getAction()) && !isRunning) {
            initCameraX();
        } else if (intent != null && "STOP_CAMERA".equalsIgnoreCase(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void initCameraX() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(160, 160)) // 鎖死超低分辨率
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis);
                isRunning = true;
            } catch (Exception e) {
                Log.e(TAG, "CameraX 初始化硬件层失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (image.getFormat() != ImageFormat.YUV_420_888) return;

            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();

            int ySize = yBuf.remaining();
            int uSize = uBuf.remaining();
            int vSize = vBuf.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuf.get(nv21, 0, ySize);
            vBuf.get(nv21, ySize, vSize);
            uBuf.get(nv21, ySize + vSize, uSize);

            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 30, out); // 30% 极小画质

            String base64Str = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            JSONObject json = new JSONObject();
            json.put("sender", "phone");
            json.put("type", "camera_stream");
            json.put("payload", base64Str);

            byte[][] rawBytes = {json.toString().getBytes(StandardCharsets.UTF_8)};
            new Thread(() -> {
                try {
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    for (Node n : nodes) {
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, rawBytes[0]);
                    }
                } catch (Exception ignored) {}
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "画面压缩投递异常", e);
        } finally {
            // 命脉：无条件关闭
            image.close();
        }
    }

    @Override
    public void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) cameraProvider.unbindAll();
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}