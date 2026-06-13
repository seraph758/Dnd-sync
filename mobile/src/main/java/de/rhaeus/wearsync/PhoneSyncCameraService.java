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
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CHANNEL_ID = "wear_sync_camera_channel";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final ExecutorService mStreamExecutor = Executors.newSingleThreadExecutor();
    private ChannelClient.ChannelCallback mChannelCallback;

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
        
        // 🎯 監聽手錶端主動開啟通道的事件
        setupPhoneChannelListener();
        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);
        Log.d(TAG, "✅ [🎉 服務啟動] PhoneSyncCameraService 建立成功，等待手錶端通道接入...");
    }

    private void setupPhoneChannelListener() {
        mChannelCallback = new ChannelClient.ChannelCallback() {
            @Override
            public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                if ("/wear-camera-stream".equals(channel.getPath())) {
                    mActiveChannel = channel;
                    mStreamExecutor.execute(() -> {
                        try {
                            mChannelOutputStream = Tasks.await(Wearable.getChannelClient(PhoneSyncCameraService.this).getOutputStream(channel));
                            Log.d(TAG, "🎯 [通道對齊成功] 已成功獲取傳輸輸出流！");
                        } catch (Exception e) {
                            Log.e(TAG, "🚨 獲取通道輸出流失敗", e);
                        }
                    });
                }
            }

            @Override
            public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
                if (channel == mActiveChannel) {
                    Log.d(TAG, "🛑 手錶端關閉了傳輸通道");
                    stopSelf();
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "🎬 Service 收到運行動作: " + action);

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            showNotificationAndStartForeground();
            isRunning = true;
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        }

        if ("WATCH_READY".equalsIgnoreCase(action)) {
            if (isRunning) {
                startCameraXDataStream();
            }
        }

        if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            Log.d(TAG, "📸 收到拍照信號，準備切換至 95 高清保存...");
            mIsCaptureRequested = true;
        }

        if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            isRunning = false;
            if (cameraProvider != null) cameraProvider.unbindAll();
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

    private void startCameraXDataStream() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

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
        } finally {
            image.close();
        }
    }

    private byte[] convertYuvToJpeg(ImageProxy image, int quality) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy[] planes = image.getPlanes();

            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int yRowStride = planes[0].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            byte[] nv21 = new byte[width * height * 3 / 2];

            // 1. 逐行複製 Y，剔除一加 12 尾部的 Padding
            for (int i = 0; i < height; i++) {
                yBuffer.position(i * yRowStride);
                yBuffer.get(nv21, i * width, width);
            }

            // 2. 逐像素精確還原 U/V 分量
            int uvIdx = width * height;
            for (int row = 0; row < height / 2; row++) {
                int vPos = row * vRowStride;
                for (int col = 0; col < width / 2; col++) {
                    nv21[uvIdx++] = vBuffer.get(vPos + col * uvPixelStride);
                    nv21[uvIdx++] = uBuffer.get(vPos + col * uvPixelStride - 1); // U分量緊鄰
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
                Wearable.getMessageClient(context).sendMessage("NO_MATTER_WHICH_NODE", UNIVERSAL_SYNC_PATH, data);
            } catch (Exception e) { Log.e(TAG, "發信失敗", e); }
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
        if (mChannelCallback != null) Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
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
