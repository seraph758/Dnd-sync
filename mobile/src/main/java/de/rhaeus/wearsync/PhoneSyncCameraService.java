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
    
    private ProcessCameraProvider cameraProvider;
    private ChannelClient.Channel mActiveChannel;
    private OutputStream mChannelOutputStream;
    
    private boolean isRunning = false;
    private volatile boolean mShouldCaptureNextFrame = false;

    private final Handler mTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimeoutRunnable = () -> {
        Log.w(TAG, "⏳ 達到45秒安全閾值，相機背景採集自動超時卸載。");
        stopForeground(true);
        stopSelf();
    };

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "🎬 Service 收到拍照模塊指令: " + action);

        // 🎯 1. 關閉指令：第一時間原地安全退出
        if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            Log.d(TAG, "🛑 執行主動關閉：安全釋放 CameraX 與藍牙長連接管道");
            isRunning = false;
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
            closeChannelSafely();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 🎯 2. 拍照指令：直接分流，絕不觸發前台挂載
        if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            Log.d(TAG, "📸 完美攔截分流：觸發後台拍照採集流程...");
            takePictureInternal();
            return START_NOT_STICKY; 
        }

        // --------------------------------------------------------------------------------
        // 🛡️ 只有 START_CAMERA 核心初始化指令，才允許建立並掛載前台通知
        // --------------------------------------------------------------------------------
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
            Log.d(TAG, "✅ 成功頂起 TYPE_CAMERA 前台相機安全防護盾");

            // 告知本地廣播（原 Bridge 残留可以保留相容，不會影響系統）
            Intent readyIntent = new Intent("de.rhaeus.wearsync.ACTION_SERVICE_READY");
            readyIntent.setPackage(getPackageName());
            sendBroadcast(readyIntent);

        } catch (Exception e) {
            Log.e(TAG, "🚨 startForeground 失敗，Android 14 FGS 後台啟動限制攔截！", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            isRunning = true;
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
            mTimeoutHandler.postDelayed(mTimeoutRunnable, 45000); 
    
            // 🎯 核心咬合：直接從傳入的 Intent 裡拿到手錶的 NodeId，原地直接開闢通道，破除死鎖！
            String nodeId = intent.getStringExtra("node_id");
            if (nodeId != null && !nodeId.isEmpty()) {
                Log.d(TAG, "🤝 收到點火指令，主動與手錶端節點 [" + nodeId + "] 建立長連接畫面傳輸管道");
                setupCameraAndChannel(nodeId);
            } else {
                Log.e(TAG, "❌ 啟動失敗：未獲取到有效的手錶 NodeId");
                stopSelf();
            }
        } 

        return START_NOT_STICKY;
    }

    private void setupCameraAndChannel(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            Log.e(TAG, "❌ 建立長連接失敗：手錶 NodeId 為空！");
            return;
        }
        mStreamExecutor.execute(() -> {
            try {
                // 1. 創立雙向傳輸流管道
                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(nodeId, "/wear-camera-stream"));
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                Log.d(TAG, "🚀 [/wear-camera-stream] 藍牙長連接管道開闢成功，等待數據注入。");

                // 2. 切回主線程點火 CameraX
                new Handler(Looper.getMainLooper()).post(this::startCameraXDataStream);

            } catch (Exception e) {
                Log.e(TAG, "🚨 開闢手錶長連接傳輸管道失敗", e);
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
                Log.d(TAG, "⚙️ CameraX 採集核心綁定成功，畫面開始向手錶推送！");

            } catch (Exception e) {
                Log.e(TAG, "初始化 CameraX 採集失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrameAndStream(@NonNull ImageProxy image) {
        if (!isRunning || mChannelOutputStream == null) {
            image.close();
            return;
        }

        try {
            // 1. 常規影格壓縮傳輸
            byte[] jpegData = convertYuvToJpeg(image);
            if (jpegData != null) {
                // 寫入影格大小與長度
                ByteBuffer header = ByteBuffer.allocate(4);
                header.putInt(jpegData.length);
                mChannelOutputStream.write(header.array());
                mChannelOutputStream.write(jpegData);
                mChannelOutputStream.flush();
            }

            // 如果手錶點了拍照，捕獲當前影格寫入手機本地相簿
            if (mShouldCaptureNextFrame) {
                mShouldCaptureNextFrame = false;
                if (jpegData != null) {
                    saveToGalleryInternal(jpegData);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "影格數據流傳輸或拍照保存失敗", e);
        } finally {
            image.close();
        }
    }

    private void takePictureInternal() {
        mShouldCaptureNextFrame = true;
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
                        Log.d(TAG, "🎯 [手錶端遠程拍照成功] 照片已保存至手機系統相簿: " + fileName);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "寫入手機相簿發生異常", e);
        }
    }

    private byte[] convertYuvToJpeg(ImageProxy image) {
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
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
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
                    Log.d(TAG, "📤 [手機端發信成功] 已向手錶傳遞指令: " + action);
                }
            } catch (Exception e) {
                Log.e(TAG, "手機向手錶同步相機狀態失敗", e);
            }
        }).start();
    }

    private void closeChannelSafely() {
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
            Log.d(TAG, "🔒 Channel 長連接管道安全釋放。");
        } catch (Exception e) {
            Log.e(TAG, "關閉通道失敗", e);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false; 
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        mStreamExecutor.shutdownNow(); 
        closeChannelSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "相機背景採集通知", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
