package de.rhaeus.wearsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
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
import androidx.camera.extensions.ExtensionsManager; 

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
    private static final int NOTIFICATION_ID = 1025; 

    private final Handler mTimeoutHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable mTimeoutRunnable = null;
    private static final int WATCH_TIMEOUT_MS = 8000; // 8秒防空轉自毀防火牆
    
    // 🎯 升級為 volatile，確保多線程可見性
    private volatile boolean isCaptureRequested = false; 
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private volatile boolean isRunning = false;

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
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相機同步中")
                .setContentText("正在等待手錶端響應...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        // 🎯 使用與建構子統一的 NOTIFICATION_ID
        startForeground(NOTIFICATION_ID, notification);

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "📥 PhoneSyncCameraService 收到 Action: " + action);

            if ("START_CAMERA".equalsIgnoreCase(action)) {
                isRunning = true;
                // 1. 異步建立管道，向手錶發送起航指令
                prepareChannelAndCamera(); 

                // 2. 🎯 開啟 8 秒超時防死鎖自毀防火牆
                if (mTimeoutRunnable != null) mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
                mTimeoutRunnable = () -> {
                    if (isRunning && cameraProvider == null) {
                        Log.e(TAG, "🚨 [超時自毀] 手錶端 8 秒內未回傳 READY 握手信號，自動關閉後台服務！");
                        stopSelf();
                    }
                };
                mTimeoutHandler.postDelayed(mTimeoutRunnable, WATCH_TIMEOUT_MS);

            } else if ("WATCH_READY".equalsIgnoreCase(action)) {
                // 3. 🎯 真正的雙向點火：手錶端 Activity 起來了，手機才綁定 CameraX
                Log.d(TAG, "🔥 [雙向握手成功] 收到手錶端 READY 信號，手機相機 Pipeline 正式點火！");
                if (mTimeoutRunnable != null) {
                    mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
                }
                startCameraPipeline();

            } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                Log.d(TAG, "🛑 收到停止相機指令，準備釋放資源。");
                sendStopSignalToWatch(); 
                stopSelf();

            } else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                Log.d(TAG, "📸 [拍照內核] 收到明確拍照指令，標記下個幀進行高清保存！");
                isCaptureRequested = true; // 觸發下一幀寫入系統相冊
            }
        }
        return START_NOT_STICKY; // 這種前台控制類服務，不需要黏性重啟，防止空轉
    }

    public static void sendCameraControlToWatchLive(Context context, String action) {
        if ("START_CAMERA".equals(action)) {
            try {
                // 🎯 為了規避 Android 14 後台拉起限制，這裡如果是後台點火，交給 BridgeActivity 中轉
                Intent bridgeIntent = new Intent(context, PhoneCameraBridgeActivity.class);
                bridgeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(bridgeIntent);
                Log.d(TAG, "⚡ [Android 14 守護者機制] 已通過過渡 Activity 安全中轉前台 FGS 權限");
            } catch (Exception e) {
                Log.e(TAG, "中轉啟動 Service 異常", e);
            }
        }

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", action); 
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
                String realWatchNodeId = null;
                for (Node node : nodes) {
                    if (node.isNearby()) { 
                        realWatchNodeId = node.getId();
                        break;
                    }
                }

                if (realWatchNodeId != null) {
                    Wearable.getMessageClient(context)
                            .sendMessage(realWatchNodeId, "/wear-universal-sync", data)
                            .addOnSuccessListener(integer -> Log.d(TAG, "🚀 已成功向手錶發送指令：" + action));
                }
            } catch (Exception e) {
                Log.e(TAG, "發送相機控制訊息崩潰", e);
            }
        }).start();
    }

    private void prepareChannelAndCamera() {
        mStreamExecutor.execute(() -> {
            try {
                Log.d(TAG, "正在建立全域唯一 Channel 長連接管道...");
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                String realWatchNodeId = null;
                for (Node node : nodes) {
                    if (node.isNearby()) {
                        realWatchNodeId = node.getId();
                        break;
                    }
                }

                if (realWatchNodeId != null) {
                    ChannelClient channelClient = Wearable.getChannelClient(this);
                    mActiveChannel = Tasks.await(channelClient.openChannel(realWatchNodeId, "/wear-camera-stream"));
                    mChannelOutputStream = Tasks.await(channelClient.getOutputStream(mActiveChannel));
                    Log.d(TAG, "🚀 全域 Channel 長連接管道建立成功！");
                }
            } catch (Exception e) {
                Log.e(TAG, "建立 Channel 管道失敗", e);
            }
            // ⚠️ 核心修正：移除此處的直接點火 startCameraPipeline()，完全交給 WATCH_READY 握手觸發！
        });
    }

    private void startCameraPipeline() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 廠商 Extension 模塊兼容易度校驗線路
                try {
                    ListenableFuture<ExtensionsManager> extensionsManagerFuture = 
                            ExtensionsManager.getInstanceAsync(this, cameraProvider);
                    ExtensionsManager extensionsManager = extensionsManagerFuture.get();

                    if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                        CameraSelector hdrSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR);
                        if (extensionsManager.isImageAnalysisSupported(hdrSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                            cameraSelector = hdrSelector;
                            Log.d(TAG, "✨ 成功啟用手機硬體級 HDR 串流優化！");
                        }
                    }
                } catch (Exception extEx) {
                    Log.w(TAG, " Extension 模組載入異常，自動降級使用標準硬體通道");
                }

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(240, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                Log.d(TAG, "🎉 CameraX Pipeline 成功繫結生命週期！");

            } catch (Exception e) {
                Log.e(TAG, "❌ 嚴重錯誤：繫結專屬相機生命週期失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(@NonNull ImageProxy image) {
        byte[] jpegData = null;
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
            
            // 🎯 優化取景流質量：平時傳給手錶 35 質量即可保證流暢
            int quality = isCaptureRequested ? 95 : 35; 
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), quality, out);
            jpegData = out.toByteArray();

            // 🎯【核心補齊】：當手錶按下拍照後，抓取當前快照直接注入手機相冊
            if (isCaptureRequested && jpegData != null) {
                isCaptureRequested = false; // 瞬間閉鎖，防止重複寫入
                saveJpegToSystemGallery(jpegData, image.getWidth(), image.getHeight());
            }

        } catch (Exception e) {
            Log.e(TAG, "畫面分析採集崩潰", e);
        } finally {
            image.close();
        }

        if (jpegData != null && jpegData.length > 0) {
            final byte[] finalData = jpegData;
            mStreamExecutor.execute(() -> {
                synchronized (mLock) { 
                    try {
                        if (isRunning && mChannelOutputStream != null) {
                            mChannelOutputStream.write(finalData);
                            mChannelOutputStream.flush();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "流寫入嘗試被安全忽略");
                    }
                }
            });
        }
    }

    // 🎯【內核擴展】：將圖片安全寫入手機系統相冊媒體庫
    private void saveJpegToSystemGallery(byte[] jpegBytes, int width, int height) {
        mStreamExecutor.execute(() -> {
            try {
                String fileName = "WearSync_" + System.currentTimeMillis() + ".jpg";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.WIDTH, width);
                values.put(MediaStore.Images.Media.HEIGHT, height);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }

                Uri itemUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (itemUri != null) {
                    try (OutputStream galleryOut = getContentResolver().openOutputStream(itemUri)) {
                        if (galleryOut != null) {
                            galleryOut.write(jpegBytes);
                            galleryOut.flush();
                            Log.d(TAG, "💾 [相冊寫入成功] 照片已安全保存至手機相冊: " + itemUri.toString());
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(itemUri, values, null, null);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "🚨 寫入系統相冊失敗", e);
            }
        });
    }

    private void sendStopSignalToWatch() {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", "STOP_CAMERA");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(this).sendMessage(n.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "📤 STOP_CAMERA 關閉信號已推送到手錶端");
            } catch (Exception e) {
                Log.e(TAG, "向手錶發送關閉指令失敗", e);
            }
        }).start();
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
                Log.d(TAG, "🔒 Channel 長連接管道安全釋放。");
            } catch (Exception e) {
                Log.e(TAG, "關閉通道失敗", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false; 
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (mTimeoutRunnable != null) {
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
        }
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
