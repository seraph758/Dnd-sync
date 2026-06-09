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
import androidx.camera.extensions.ExtensionsManager; 

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String CHANNEL_ID = "wear_sync_camera_channel";
    private static final int NOTIFICATION_ID = 8899; // 🎯 統一使用此 ID

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private boolean isRunning = false;
    
    private final Object mLock = new Object();

    private ChannelClient.Channel mActiveChannel = null;
    private OutputStream mChannelOutputStream = null;
    private final ExecutorService mStreamExecutor = Executors.newSingleThreadExecutor();

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        // 🎯 修正：必須在服務建立時先接通通知管道，否則 startForeground 會因找不到 Channel 閃退
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_CAMERA".equals(intent.getAction())) {
            if (!isRunning) {
                isRunning = true;
                lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("WearSync 遠端取景器")
                        .setContentText("正在為手錶端提供高性能影像傳輸...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .build();

                // 🎯 修正：將硬編碼的 2026 全部替換為定義好的 NOTIFICATION_ID 常數
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14 (API 34) 及以上必須帶上相機類型引數
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
                } else {
                    // Android 13 及以下保持標準啟動
                    startForeground(NOTIFICATION_ID, notification);
                }
                
                prepareChannelAndCamera();
            }
        } else if (intent != null && "STOP_CAMERA".equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void prepareChannelAndCamera() {
        mStreamExecutor.execute(() -> {
            try {
                Log.d(TAG, "正在建立全局唯一 Channel 長連接管道...");
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (!nodes.isEmpty()) {
                    String targetNodeId = nodes.get(0).getId();
                    ChannelClient channelClient = Wearable.getChannelClient(this);
                    
                    mActiveChannel = Tasks.await(channelClient.openChannel(targetNodeId, "/wear-camera-stream"));
                    mChannelOutputStream = Tasks.await(channelClient.getOutputStream(mActiveChannel));
                    Log.d(TAG, "🚀 全局 Channel 長連接管道建立成功，自來水管已接通！");
                }
            } catch (Exception e) {
                Log.e(TAG, "建立 Channel 長連接管道失敗", e);
            }

            ContextCompat.getMainExecutor(this).execute(this::startCameraPipeline);
        });
    }

    private void startCameraPipeline() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                try {
                    ListenableFuture<ExtensionsManager> extensionsManagerFuture = 
                            ExtensionsManager.getInstanceAsync(this, cameraProvider);
                    
                    ExtensionsManager extensionsManager = extensionsManagerFuture.get();
                    
                    if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                        cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR);
                        Log.d(TAG, "✨ 成功啟用手機專屬硬體級 HDR 演算法優化！");
                    } else if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.NIGHT)) {
                        cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.NIGHT);
                        Log.d(TAG, "✨ 成功啟用手機專屬硬體級 夜景演算法優化！");
                    }
                } catch (Exception extEx) {
                    Log.w(TAG, "當前手機硬體不支持廠商 Extension 擴充，自動降級使用標準硬體通道: " + extEx.getMessage());
                }

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(240, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

            } catch (Exception e) {
                Log.e(TAG, "綁定專屬相機生命週期失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(@NonNull ImageProxy image) {
        try {
            if (!isRunning || mChannelOutputStream == null) {
                image.close();
                return;
            }

            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 35, out);
            byte[] jpegData = out.toByteArray();

            mStreamExecutor.execute(() -> {
                synchronized (mLock) { 
                    try {
                        if (isRunning && mChannelOutputStream != null) {
                            mChannelOutputStream.write(jpegData);
                            mChannelOutputStream.flush();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "手錶端已關閉，流寫入嘗試被安全忽略，無 FC 風險");
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "畫面分析採集崩潰", e);
        } finally {
            image.close();
        }
    }

    private void closeChannelSafely() {
        synchronized (mLock) {
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
                Log.d(TAG, "🔒 唯一的 Channel 長連接管道已在同步鎖內安全釋放。");
            } catch (Exception e) {
                Log.e(TAG, "關閉通道失敗", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false; 
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
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
