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
import androidx.camera.extensions.ExtensionsManager; // 方案 A 廠商優化引進

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
    private static final int NOTIFICATION_ID = 8899;

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private boolean isRunning = false;
    
    // 互斥鎖對象
    private final Object mLock = new Object();

    // 🚀 長連接核心：持久化維護唯一的 Channel 和其輸出流，防止通道鎖死
    private ChannelClient.Channel mActiveChannel = null;
    private OutputStream mChannelOutputStream = null;
    private final ExecutorService mStreamExecutor = Executors.newSingleThreadExecutor();

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_CAMERA".equals(intent.getAction())) {
            if (!isRunning) {
                isRunning = true;
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("WearSync 遠端取景器")
                        .setContentText("正在為手錶端提供高性能影像傳輸...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();
                startForeground(NOTIFICATION_ID, notification);
                
                // 異步初始長管道並掛載相機
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
                    
                    // 1. 僅在此處開啟一次長連接
                    mActiveChannel = Tasks.await(channelClient.openChannel(targetNodeId, "/wear-camera-stream"));
                    mChannelOutputStream = Tasks.await(channelClient.getOutputStream(mActiveChannel));
                    Log.d(TAG, "🚀 全局 Channel 長連接管道建立成功，自來水管已接通！");
                }
            } catch (Exception e) {
                Log.e(TAG, "建立 Channel 長連接管道失敗", e);
            }

            // 2. 切回主執行緒初始化 CameraX 方案 A 擴充
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

                // 🎯【修正核心】ListenableFuture 應該使用 .get() 而非 Tasks.await()
                try {
                    ListenableFuture<ExtensionsManager> extensionsManagerFuture = 
                            ExtensionsManager.getInstanceAsync(this, cameraProvider);
                    
                    // 順著 CameraX 的監聽執行緒安全解開 Future 鎖
                    ExtensionsManager extensionsManager = extensionsManagerFuture.get();
                    
                    // 檢查基本夜景或 HDR 優化能力是否獲得廠商底層硬體解鎖
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
            // 如果已經在準備銷毀了，直接攔截，不加入執行緒池隊列
            if (!isRunning || mChannelOutputStream == null) {
                image.close();
                return;
            }

            // 提取 YUV 位元組資料
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

            // 壓縮成低頻寬壓力的優質預覽 JPEG
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 35, out);
            byte[] jpegData = out.toByteArray();

            // 2. 灌入數據時，加入安全保護
            mStreamExecutor.execute(() -> {
                synchronized (mLock) { // 🎯 鎖定臨界區
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
        // 3. 銷毀時同步鎖定，確保當前正在寫入的那一幀寫完，隨後的任務全部被拒絕
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
        isRunning = false; // 第一步：立刻切斷採集標誌位
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        
        // 第二步：立刻強行停止執行緒池，清空裡面排隊的所有預覽幀任务
        mStreamExecutor.shutdownNow(); 
        
        // 第三步：同步關閉管道
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
