package de.rhaeus.wearsync;

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
    private static final String CHANNEL_ID = "wear_camera_sync_channel";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider = null;
    private ExecutorService mStreamExecutor;

    private ChannelClient.Channel mActiveChannel = null;
    private OutputStream mChannelOutputStream = null;
    private ChannelClient.ChannelCallback mChannelCallback = null;

    private final Object mLock = new Object();
    private boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mStreamExecutor = Executors.newSingleThreadExecutor();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        createNotificationChannel();
        Log.d(TAG, "✅ [服務啟動] PhoneSyncCameraService 建立成功");
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "🎬 Service 收到運行動作: " + action);

        if ("START_CAMERA".equalsIgnoreCase(action)) {
            if (!isRunning) {
                isRunning = true;
                lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                
                // 立即掛上前台，防禦高版本系統強殺
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("相機同步中")
                        .setContentText("正在與 WearOS 設備同步影像畫面...")
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();
                startForeground(8848, notification);

                setupChannelAndCameraX();
            }
        } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            executeServiceShutdown();
        }

        return START_NOT_STICKY;
    }

    private void setupChannelAndCameraX() {
        new Thread(() -> {
            try {
                Log.d(TAG, "⏳ 正在尋找活躍的手錶節點並建立長連接管道...");
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) {
                    Log.e(TAG, "❌ 未能找到任何活躍的手錶節點");
                    return;
                }
                String targetNodeId = nodes.get(0).getId();

                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(targetNodeId, UNIVERSAL_SYNC_PATH));
                Log.d(TAG, "⚡ 藍牙 Channel 建立完畢，正在獲取傳輸輸出流...");
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));

                mChannelCallback = new ChannelClient.ChannelCallback() {
                    @Override
                    public void onChannelClosed(@NonNull ChannelClient.Channel channel, int closeReason, int appSpecificErrorCodeCode) {
                        Log.w(TAG, "🛑 手錶端主動斷開了 Channel 管道，外部原因碼: " + closeReason);
                        executeServiceShutdown();
                    }
                };
                Wearable.getChannelClient(this).registerChannelCallback(mActiveChannel, mChannelCallback);

                // 管道通了，點火相機
                initCameraX();

            } catch (Exception e) {
                Log.e(TAG, "建立傳輸通道或點火相機失敗", e);
                executeServiceShutdown();
            }
        }).start();
    }

    private void initCameraX() {
        mainThreadInvoke(() -> {
            try {
                ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
                cameraProvider = providerFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::processFrameAndStream);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                Log.d(TAG, "🚀 CameraX 採集點火成功！開始向手錶噴射數據流...");

            } catch (Exception e) {
                Log.e(TAG, "CameraX 初始化異常", e);
            }
        });
    }

    private void processFrameAndStream(@NonNull ImageProxy image) {
        if (!isRunning) {
            image.close();
            return;
        }

        try {
            if (image.getFormat() == ImageFormat.YUV_420_888) {
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
                // 保持原汁原味的高畫質壓縮
                yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 85, out);
                byte[] jpegData = out.toByteArray();

                synchronized (mLock) {
                    if (mChannelOutputStream != null && isRunning) {
                        // ✨ 對齊能亮屏的版本：直接寫入原始 JPEG 字節，沒有任何多餘的長度頭
                        mChannelOutputStream.write(jpegData);
                        mChannelOutputStream.flush();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "傳輸影像影格失敗", e);
        } finally {
            image.close();
        }
    }

    private void executeServiceShutdown() {
        isRunning = false;
        mainThreadInvoke(() -> {
            try {
                lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
                if (cameraProvider != null) {
                    cameraProvider.unbindAll();
                    cameraProvider = null;
                }
                closeChannelSafely();
                stopForeground(true);
                stopSelf();
                Log.d(TAG, "🛑 相機背景採集服務已完全安全退出，硬體資源全部釋放。");
            } catch (Exception e) {
                Log.e(TAG, "執行銷毀流程異常", e);
            }
        });
    }

    private void closeChannelSafely() {
        synchronized (mLock) {
            try {
                if (mChannelCallback != null) {
                    Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
                    mChannelCallback = null;
                }
                if (mChannelOutputStream != null) {
                    mChannelOutputStream.close();
                    mChannelOutputStream = null;
                }
                if (mActiveChannel != null) {
                    Wearable.getChannelClient(this).close(mActiveChannel);
                    mActiveChannel = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "釋放管道組件失敗", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        executeServiceShutdown();
        if (mStreamExecutor != null) {
            mStreamExecutor.shutdownNow();
        }
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

    private void mainThreadInvoke(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
