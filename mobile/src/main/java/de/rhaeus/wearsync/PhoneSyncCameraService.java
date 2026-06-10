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
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.MessageClient;
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

public class PhoneSyncCameraService extends Service implements LifecycleOwner, MessageClient.OnMessageReceivedListener {
    private static final String TAG = "WearSync_CameraService";
    private static final String CHANNEL_ID = "PhoneSyncCameraChannel";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private ExecutorService mStreamExecutor;
    
    private final Object mLock = new Object();
    private ChannelClient.Channel mActiveChannel = null;
    private OutputStream mChannelOutputStream = null;
    private volatile boolean isRunning = false;

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        mStreamExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
        
        // 注册全局消息监听：用来接收手表发来的 "STOP_CAMERA" 退出信令
        Wearable.getMessageClient(this).addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isRunning) return START_STICKY;
        isRunning = true;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("相機背景同步中")
                .setContentText("正在實時將手機畫面傳輸至手錶...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(10086, notification);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        // 异步打通连接，连通后再开启相机预览
        new Thread(this::initChannelAndCamera).start();

        return START_STICKY;
    }

    private void initChannelAndCamera() {
        synchronized (mLock) {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) {
                    Log.e(TAG, "❌ 找不到任何可連線的手錶節點");
                    return;
                }
                String targetNodeId = nodes.get(0).getId();

                // 1. 彻底解决全黑断层：建立长连接双向 Channel 管道
                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(targetNodeId, UNIVERSAL_SYNC_PATH));
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                Log.d(TAG, "🔑 Channel 管道與 OutputStream 建立成功！");

                // 🎯【痛点一协议对齐】：给手表发 START_CAMERA 消息，强行把手表界面顶起来！
                JSONObject json = new JSONObject();
                json.put("type", "camera_control");
                json.put("action", "START_CAMERA");
                byte[] alertData = json.toString().getBytes(StandardCharsets.UTF_8);
                Wearable.getMessageClient(this).sendMessage(targetNodeId, UNIVERSAL_SYNC_PATH, alertData);
                Log.d(TAG, "🚀 已向手錶發送 START_CAMERA 喚醒通知");

                // 2. 管道打通后，瞬间激活手机端 CameraX 进行流采集
                startCameraX();

            } catch (Exception e) {
                Log.e(TAG, "🚨 建立 Channel 通道或相機初始化失敗", e);
            }
        }
    }

    private void startCameraX() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 240)) // 降低传输压力防止积压变黑
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::analyzeImageFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                Log.d(TAG, "📸 CameraX 分析器已成功綁定，流畫面開始泵出");

            } catch (Exception e) {
                Log.e(TAG, "綁定 CameraX 失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImageFrame(@NonNull ImageProxy image) {
        if (!isRunning || mChannelOutputStream == null) {
            image.close();
            return;
        }
        try {
            // YUV_420_888 压缩为 JPEG 字节流
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
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 50, bos);
            byte[] jpegBytes = bos.toByteArray();

            // 通过已经对齐的 ChannelOutputStream 推送至手表
            synchronized (mLock) {
                if (mChannelOutputStream != null && isRunning) {
                    // 先写帧大小标志，再写入图片载荷数据
                    ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                    lengthBuffer.putInt(jpegBytes.length);
                    mChannelOutputStream.write(lengthBuffer.array());
                    mChannelOutputStream.write(jpegBytes);
                    mChannelOutputStream.flush();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, " 傳輸影格畫面異常", e);
        } finally {
            image.close();
        }
    }

    // 🎯【痛点二：收到手表发回的退出指令，手机自行在内部 stopSelf 优雅自杀】
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        try {
            String jsonStr = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("camera_control".equalsIgnoreCase(type) && "STOP_CAMERA".equalsIgnoreCase(action)) {
                Log.d(TAG, "🛑 收到來自手錶端的終止拍照指令，手機服務準備自我銷毀...");
                isRunning = false;
                stopSelf(); // 响应退出，优雅自杀
            }
        } catch (Exception e) {
            Log.e(TAG, "解析手錶反向指令異常", e);
        }
    }

    private void closeChannelSafely() {
        synchronized (mLock) {
            try {
                if (mChannelOutputStream != null) {
                    mChannelOutputStream.close();
                    mChannelOutputStream = null;
                }
                if (mActiveChannel != null) {
                    Wearable.getChannelClient(this).close(mActiveChannel);
                    mActiveChannel = null;
                }
                Log.d(TAG, "🔒 Channel 管道與輸出流在同步鎖內安全釋放。");
            } catch (Exception e) {
                Log.e(TAG, "釋放通道失敗", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        Wearable.getMessageClient(this).removeListener(this); // 移除监听
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        mStreamExecutor.shutdownNow();
        closeChannelSafely();
        super.onDestroy();
        Log.d(TAG, "🏁 PhoneSyncCameraService 已徹底安全退出");
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
