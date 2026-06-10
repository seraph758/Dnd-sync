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
    private static final int NOTIFICATION_ID = 8899; 

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

                try {
                    // 🎯【核心修正】：加入更安全的異常阻斷機制，確保遵守 Android 14 規範
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                    Log.d(TAG, "✅ 成功以 FOREGROUND_SERVICE_TYPE_CAMERA 啟動前台服務");
                    prepareChannelAndCamera();
                } catch (SecurityException se) {
                    Log.e(TAG, "❌ 權限或前台狀態校驗失敗！拒絕盲目啟動以防止 FC：", se);
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
        } else if (intent != null && "STOP_CAMERA".equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /**
     * 🎯 靜態工具解耦方法：供 MainFragment 點擊按鈕時調用
     * 完美破局 Android 14 限制：在點擊時由活躍的 UI 立刻拉起本地 Service，隨後發射信令拉起手錶 UI！
     */
    public static void sendCameraControlToWatchLive(Context context, String action) {
        // 1. 🎯【安全破局關鍵】：既然現在使用者正在點擊手機 UI，立刻由前台 UI 順暢啟動 Service，100% 免疫背景限制！
        if ("START_CAMERA".equals(action)) {
            try {
                Intent serviceIntent = new Intent(context, PhoneSyncCameraService.class);
                serviceIntent.setAction("START_CAMERA");
                ContextCompat.startForegroundService(context, serviceIntent);
                Log.d(TAG, "⚡ [Android 14 守護者機制] 已由前台 UI 點擊事件提前安全拉起 Service");
            } catch (Exception e) {
                Log.e(TAG, "前台 UI 啟動 Service 異常", e);
            }
        }

        // 2. 非同步通知手錶端拉起 UI 畫面
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
                            .addOnSuccessListener(integer -> Log.d(TAG, "🚀 已成功向手錶發送指令：" + action))
                            .addOnFailureListener(e -> Log.e(TAG, "❌ 向手錶發送信令失敗"));
                } else {
                    Log.e(TAG, "❌ 發送失敗：當下未偵測到任何有效的在線手錶設備！");
                }
            } catch (Exception e) {
                Log.e(TAG, "發送相機控制訊息崩潰", e);
            }
        }).start();
    }

    private void prepareChannelAndCamera() {
        mStreamExecutor.execute(() -> {
            try {
                Log.d(TAG, "正在建立全局唯一 Channel 長連接管道...");
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
                    Log.d(TAG, "🚀 全局 Channel 長連接管道建立成功，自來水管已接通！");
                } else {
                    Log.w(TAG, "⚠️ 未找到有效手錶節點，暫時掛起 Channel，等待手錶接入");
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
                    Log.w(TAG, "自動降級使用標準硬體通道: " + extEx.getMessage());
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
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 35, out);
            jpegData = out.toByteArray();

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
                        Log.w(TAG, "手錶端已關閉，流寫入嘗試被安全忽略");
                    }
                }
            });
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
