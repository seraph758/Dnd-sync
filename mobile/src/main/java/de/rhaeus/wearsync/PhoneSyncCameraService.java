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

                // 🎯【UI简中修正】
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("WearSync 远程取景器")
                        .setContentText("正在为手表端提供高性能影像传输...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .build();

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                    Log.d(TAG, "✅ 成功以 FOREGROUND_SERVICE_TYPE_CAMERA 启动前台服务");
                    prepareChannelAndCamera();
                } catch (SecurityException se) {
                    Log.e(TAG, "❌ 权限或前台状态校验失败！拒绝盲目启动以防止 FC：", se);
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
        } else if (intent != null && "STOP_CAMERA".equals(intent.getAction())) {
            // 🎯【核心修正】：允许手机 UI 通过 STOP_CAMERA 指令将其主动关闭，不依赖手表
            Log.d(TAG, "🛑 收到明确的停止 Action 指令，手机端主动关闭相机服务...");
            isRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    public static void sendCameraControlToWatchLive(Context context, String action) {
        if ("START_CAMERA".equals(action)) {
            try {
                Intent serviceIntent = new Intent(context, PhoneSyncCameraService.class);
                serviceIntent.setAction("START_CAMERA");
                ContextCompat.startForegroundService(context, serviceIntent);
                Log.d(TAG, "⚡ [Android 14 守护者机制] 已由前台 UI 点击事件提前安全拉起 Service");
            } catch (Exception e) {
                Log.e(TAG, "前台 UI 启动 Service 异常", e);
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
                            .addOnSuccessListener(integer -> Log.d(TAG, "🚀 已成功向手表发送指令：" + action))
                            .addOnFailureListener(e -> Log.e(TAG, "❌ 向手表发送信令失败"));
                } else {
                    Log.e(TAG, "❌ 发送失败：当下未侦测到任何有效的在线手表设备！");
                }
            } catch (Exception e) {
                Log.e(TAG, "发送相机控制消息崩溃", e);
            }
        }).start();
    }

    private void prepareChannelAndCamera() {
        mStreamExecutor.execute(() -> {
            try {
                Log.d(TAG, "正在建立全局唯一 Channel 长连接管道...");
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
                    Log.d(TAG, "🚀 全局 Channel 长连接管道建立成功，自来水管已接通！");
                } else {
                    Log.w(TAG, "⚠️ 未找到有效手表节点，暂时挂起 Channel，等待手表接入");
                }
            } catch (Exception e) {
                Log.e(TAG, "建立 Channel 长连接管道失败", e);
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

                    CameraSelector hdrSelector = null;
                    CameraSelector nightSelector = null;

                    if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                        hdrSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR);
                        if (!extensionsManager.isImageAnalysisSupported(hdrSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                            Log.w(TAG, "⚠️ 检测到目前硬件不支持在 HDR 开启时进行影像串流采集，放弃启用 HDR 特效。");
                            hdrSelector = null;
                        }
                    }

                    if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.NIGHT)) {
                        nightSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.NIGHT);
                        if (!extensionsManager.isImageAnalysisSupported(nightSelector, androidx.camera.extensions.ExtensionMode.NIGHT)) {
                            Log.w(TAG, "⚠️ 检测到目前硬件不支持在夜景模式开启时进行影像串流采集，放弃启用夜景特效。");
                            nightSelector = null;
                        }
                    }

                    if (hdrSelector != null) {
                        cameraSelector = hdrSelector;
                        Log.d(TAG, "✨ 通过兼容性校验，成功启用手机硬件级 HDR 串流优化！");
                    } else if (nightSelector != null) {
                        cameraSelector = nightSelector;
                        Log.d(TAG, "✨ 通过兼容性校验，成功启用手机硬件级 夜景串流优化！");
                    } else {
                        Log.d(TAG, "ℹ️ 为了保证低延迟取景串流稳定性，自动安全采用标准硬件通道。");
                    }

                } catch (Exception extEx) {
                    Log.w(TAG, "厂商 Extension 模块加载异常，自动降级使用标准硬件通道: " + extEx.getMessage());
                }

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(240, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                Log.d(TAG, "🎉 CameraX Pipeline 成功绑定生命周期，数据串流已就绪！");

            } catch (Exception e) {
                Log.e(TAG, "❌ 严重错误：绑定专属相机生命周期失败，强制自杀退出，防止卡死后台！", e);
                // 🎯【核心修正】：绑定失败（例如硬件不兼容抛错时）立即自我销毁，防止前台通知常驻卡死
                isRunning = false;
                stopSelf();
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
            Log.e(TAG, "画面分析采集崩溃", e);
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
                        Log.w(TAG, "手表端已关闭，流写入尝试被安全忽略");
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
                Log.d(TAG, "🔒 唯一的 Channel 长连接管道已在同步锁内安全释放。");
            } catch (Exception e) {
                Log.e(TAG, "关闭通道失败", e);
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
                    CHANNEL_ID, "相机后台采集通知", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
