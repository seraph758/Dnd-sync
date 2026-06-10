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
    private static final String CHANNEL_ID = "camera_bg_sync_channel";
    private static final int NOTIFICATION_ID = 8899;

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

        // 🎯 這裡修正為 Android 系統自帶的相機圖標，確保 100% 順利編譯通過
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WearSync 相機同步服務")
                .setContentText("正在背景提供手錶遠端影像串流...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        if (!isRunning) {
            isRunning = true;
            setupCamera();
            openChannelStreamToWatch();
        }
        return START_NOT_STICKY;
    }

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(mStreamExecutor, this::processImageProxy);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                Log.d(TAG, "📸 CameraX 背景 Analysis 綁定成功。");
            } catch (Exception e) {
                Log.e(TAG, "設置相機失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void openChannelStreamToWatch() {
        new Thread(() -> {
            try {
                ChannelClient channelClient = Wearable.getChannelClient(this);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) return;

                String targetNode = nodes.get(0).getId();
                ChannelClient.Channel channel = Tasks.await(channelClient.openChannel(targetNode, "/wear-camera-stream"));

                synchronized (mLock) {
                    mActiveChannel = channel;
                    mChannelOutputStream = Tasks.await(channelClient.getOutputStream(mActiveChannel));
                    Log.d(TAG, "🚀 唯一的 Channel 長連接管道與 OutputStream 建立成功。");
                }
            } catch (Exception e) {
                Log.e(TAG, "建立通道串流失敗", e);
            }
        }).start();
    }

    private void processImageProxy(ImageProxy image) {
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

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
                yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 50, out);
                byte[] finalData = out.toByteArray();

                mStreamExecutor.execute(() -> {
                    synchronized (mLock) {
                        try {
                            if (isRunning && mChannelOutputStream != null) {
                                // 🎯 新通訊協議：先寫入 4 字節長度頭，再發送內容
                                byte[] lengthHeader = ByteBuffer.allocate(4).putInt(finalData.length).array();
                                mChannelOutputStream.write(lengthHeader);
                                mChannelOutputStream.write(finalData);
                                mChannelOutputStream.flush();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "串流管道已安全結束或關閉");
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "影像轉換失敗", e);
        } finally {
            image.close();
        }
    }

    public static void sendCameraControlToWatchLive(Context ctx, String action) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("sender", "phone");
                json.put("type", "camera_control");
                json.put("action", action);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(ctx).getConnectedNodes());
                for (Node n : nodes) {
                    Wearable.getMessageClient(ctx).sendMessage(n.getId(), "/wear-universal-sync", data);
                }
                Log.d(TAG, "📱 手機主動向手錶下發相機控制命令: " + action);
            } catch (Exception e) {
                Log.e(TAG, "手機向手錶下發相機指令失敗", e);
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
        
        // 🎯 手機退出時，同步命令手錶端關閉 Activity
        sendCameraControlToWatchLive(this, "STOP_CAMERA");

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
