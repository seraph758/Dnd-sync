package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
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
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CHANNEL_ID = "wear_sync_camera_channel";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final ExecutorService mStreamExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ProcessCameraProvider cameraProvider;
    private ChannelClient.Channel mActiveChannel;
    private OutputStream mChannelOutputStream;

    private boolean isRunning = false;
    private volatile boolean mIsCaptureRequested = false;

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        Log.d(TAG, "✅ [🎉 點火成功] PhoneSyncCameraService 已成功創建！");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "🎬 Service 收到運作指令: " + action);

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            showNotificationAndStartForeground();
            isRunning = true;
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            setupActiveChannel();
        }

        if ("WATCH_READY".equalsIgnoreCase(action)) {
            if (isRunning) {
                startCameraXDataStream();
            }
        }

        if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            mIsCaptureRequested = true;
        }

        if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            isRunning = false;
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
            sendCameraControlToWatchLive(this, "STOP_CAMERA");
            closeChannelSafely();
            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void showNotificationAndStartForeground() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相機同步中")
                .setContentText("正在傳輸實時觀景窗畫面至手錶...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(99, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(99, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "🚨 startForeground 失敗", e);
        }
    }

    private void setupActiveChannel() {
        mStreamExecutor.execute(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) return;
                String nodeId = nodes.get(0).getId();
                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(nodeId, "/wear-camera-stream"));
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                Log.d(TAG, "🚀 [/wear-camera-stream] 傳輸通道開通成功，等待手錶回應 WATCH_READY");
            } catch (Exception e) {
                Log.e(TAG, "🚨 建立通道失敗", e);
            }
        });
    }

    private void startCameraXDataStream() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                // 🎯 採用標準 4:3 高相容性解析度，徹底避開硬體不支持 1:1 分辨率引起的抽樣混亂
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::processFrameAndStream);
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                Log.d(TAG, "⚙️ CameraX 相機硬體採集已正式啟動");
            } catch (Exception e) {
                Log.e(TAG, "初始化 CameraX 失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrameAndStream(@NonNull ImageProxy image) {
        if (!isRunning || mChannelOutputStream == null) {
            image.close();
            return;
        }

        try {
            int quality = mIsCaptureRequested ? 95 : 35;
            byte[] jpegData = convertYuvToJpeg(image, quality);

            if (jpegData != null) {
                int len = jpegData.length;
                byte[] header = new byte[4];
                header[0] = (byte) ((len >> 24) & 0xFF);
                header[1] = (byte) ((len >> 16) & 0xFF);
                header[2] = (byte) ((len >> 8) & 0xFF);
                header[3] = (byte) (len & 0xFF);

                synchronized (this) {
                    if (mChannelOutputStream != null) {
                        mChannelOutputStream.write(header);
                        mChannelOutputStream.write(jpegData);
                        mChannelOutputStream.flush();
                    }
                }

                if (mIsCaptureRequested) {
                    mIsCaptureRequested = false;
                    saveToGalleryInternal(jpegData);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "傳輸影像影格失敗", e);
        } final {
            image.close();
        }
    }

    // 🎯 根治手錶端實時預覽花屏的核心：逐行修剪記憶體步長，完美還原純淨 YUV 流
    private byte[] convertYuvToJpeg(ImageProxy image, int quality) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy[] planes = image.getPlanes();

            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int yRowStride = planes[0].getRowStride();
            int uRowStride = planes[1].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            // 建立標準的緊湊 NV21 陣列空間
            byte[] nv21 = new byte[width * height * 3 / 2];

            // 1. 逐行複製 Y 分量，嚴格剔除每行末尾一加 12 硬體產生的補零字節（Padding）
            for (int i = 0; i < height; i++) {
                yBuffer.position(i * yRowStride);
                yBuffer.get(nv21, i * width, width);
            }

            // 2. 逐行逐像素精確提取 U/V 分量，徹底抹平 Stride 錯位引起的花屏綠條
            int uvIdx = width * height;
            for (int row = 0; row < height / 2; row++) {
                int vPos = row * vRowStride;
                int uPos = row * uRowStride;
                for (int col = 0; col < width / 2; col++) {
                    nv21[uvIdx++] = vBuffer.get(vPos + col * uvPixelStride);
                    nv21[uvIdx++] = uBuffer.get(uPos + col * uvPixelStride);
                }
            }

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), quality, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "YUV精準解包對齊失敗", e);
            return null;
        }
    }

    private void saveToGalleryInternal(byte[] jpegData) {
        try {
            String fileName = "WearSync_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
            }

            Uri extUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri itemUri = getContentResolver().insert(extUri, values);

            if (itemUri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(itemUri)) {
                    if (os != null) {
                        os.write(jpegData);
                        os.flush();
                        sendCameraControlToWatchLive(this, "TAKE_PICTURE_DONE");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "儲存相片失敗", e);
        }
    }

    public static void sendCameraControlToWatchLive(Context context, String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", action);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(context).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "向手錶發信失敗", e);
            }
        }).start();
    }

    private void closeChannelSafely() {
        try {
            if (mChannelOutputStream != null) { mChannelOutputStream.close(); mChannelOutputStream = null; }
            if (mActiveChannel != null) { Wearable.getChannelClient(this).close(mActiveChannel); mActiveChannel = null; }
        } catch (Exception e) { Log.e(TAG, "關閉通道失敗", e); }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) cameraProvider.unbindAll();
        closeChannelSafely();
        mStreamExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "相機背景採集通知", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
