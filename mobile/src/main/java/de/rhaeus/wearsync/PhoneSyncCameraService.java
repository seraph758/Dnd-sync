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

    // 8秒安全自毀定時器：如果手錶界面的 WATCH_READY 超時未到，主動自毀
    private final Runnable mWatchdogTimeoutRunnable = () -> {
        Log.w(TAG, "⏳ 8秒內未收到手錶 WATCH_READY 點火信號，執行安全自毀...");
        stopSelf();
    };

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "🎬 Service 收到指令: " + action);

        if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            Log.d(TAG, "📸 收到拍照信號，準備切換至 95 高清保存...");
            mIsCaptureRequested = true;
            return START_NOT_STICKY; 
        }

        if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            Log.d(TAG, "🛑 執行主動關閉：安全釋放相機與管道");
            isRunning = false;
            mHandler.removeCallbacks(mWatchdogTimeoutRunnable);
            if (cameraProvider != null) {
                cameraProvider.unbindAll(); // 先停採集，防止寫入已關閉的流
            }
            sendCameraControlToWatchLive(this, "STOP_CAMERA"); 
            closeChannelSafely();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            showNotificationAndStartForeground();
            isRunning = true;
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            
            // 🎯 步驟 1：開啟 8 秒自毀定時器，同時先與手錶建立 Channel 管道，但不點火相機
            mHandler.removeCallbacks(mWatchdogTimeoutRunnable);
            mHandler.postDelayed(mWatchdogTimeoutRunnable, 8000);
            setupActiveChannel();
        }

        if ("WATCH_READY".equalsIgnoreCase(action)) {
            // 🎯 步驟 3：手錶安全點火信號到達，移除自毀定時器，相機正式點火！
            Log.d(TAG, "🤝 [點火成功] 收到手錶端就緒信號，安全移除自毀定時器。啟動相機資料流！");
            mHandler.removeCallbacks(mWatchdogTimeoutRunnable);
            if (isRunning) {
                startCameraXDataStream();
            }
        }

        return START_NOT_STICKY;
    }

    private void showNotificationAndStartForeground() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相機背景採集")
                .setContentText("正在為手錶端提供同步畫面...")
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
            stopSelf();
        }
    }

    private void setupActiveChannel() {
        mStreamExecutor.execute(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) {
                    Log.e(TAG, "❌ 未發現可用手錶節點");
                    return;
                }
                String nodeId = nodes.get(0).getId();
                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(nodeId, "/wear-camera-stream"));
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                Log.d(TAG, "🚀 [/wear-camera-stream] 管道建立成功，等待手錶端 Ready 信號點火...");
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

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::processFrameAndStream);
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                Log.d(TAG, "⚙️ CameraX 採集核心綁定成功，開始高速推送影格資料");
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
            // 🎯 根據拍照狀態執行歷史成功的雙重質量策略
            int quality = 35; // 預設日常取景質量為 35
            if (mIsCaptureRequested) {
                quality = 95; // 拍照瞬間切換至 95 高清
            }

            byte[] jpegData = convertYuvToJpeg(image, quality);
            if (jpegData != null) {
                // 🎯 核心修正：回歸純粹的流式 JPEG 位元組流！完全不手動寫長度頭部，杜絕花屏！
                synchronized (this) {
                    if (mChannelOutputStream != null) {
                        mChannelOutputStream.write(jpegData);
                        mChannelOutputStream.flush();
                    }
                }

                if (mIsCaptureRequested) {
                    mIsCaptureRequested = false;
                    Log.d(TAG, "📸 高清影格捕獲成功，寫入手機相簿...");
                    saveToGalleryInternal(jpegData);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "資料傳輸失敗", e);
        } finally {
            image.close();
        }
    }

    private byte[] convertYuvToJpeg(ImageProxy image, int quality) {
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

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), quality, out);
            return out.toByteArray();
        } catch (Exception e) {
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
                        Log.d(TAG, "🎯 照片成功儲存。");

                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(itemUri);
                        sendBroadcast(mediaScanIntent);
                        
                        sendCameraControlToWatchLive(this, "TAKE_PICTURE_DONE");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "寫入手機相簿發生異常", e);
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
                Log.e(TAG, "通訊失敗", e);
            }
        }).start();
    }

    private void closeChannelSafely() {
        synchronized (this) {
            try {
                if (mChannelOutputStream != null) { 
                    mChannelOutputStream.flush();
                    mChannelOutputStream.close(); 
                    mChannelOutputStream = null; 
                }
                if (mActiveChannel != null) { 
                    Wearable.getChannelClient(this).close(mActiveChannel); 
                    mActiveChannel = null; 
                }
                Log.d(TAG, "🔒 藍牙發送端管道已安全關閉銷毀");
            } catch (Exception e) { 
                Log.e(TAG, "關閉通道失敗", e); 
            }
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        mHandler.removeCallbacks(mWatchdogTimeoutRunnable);
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
