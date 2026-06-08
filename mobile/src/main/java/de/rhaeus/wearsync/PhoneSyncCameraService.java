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
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PhoneSyncCameraService extends Service implements LifecycleOwner {
    private static final String TAG = "WearSync_CameraService";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    private static final String CHANNEL_ID = "camera_service_channel";
    
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private boolean isRunning = false;

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
        String action = intent != null ? intent.getAction() : null;
        
        if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            Log.d(TAG, "🛑 收到停止命令，正在關閉前台服務與相機元件");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isRunning) {
            Log.d(TAG, "📸 CameraService 已經在運行中，略過重複啟動");
            return START_STICKY;
        }

        isRunning = true;
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        // 綁定前台通知
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("雙端相機流同步中")
                .setContentText("正在背景安全捕捉相機畫面並傳輸至手錶...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        
        startForeground(2026, notification);
        
        // 啟動 CameraX 影像分析
        startCameraPipeline();

        return START_STICKY;
    }

    private void startCameraPipeline() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                // 使用低解析度加速傳輸與解碼，完美適配手錶小螢幕
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(240, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                Log.d(TAG, "🚀 CameraX 影像分析採集鏈路成功建立！");

            } catch (Exception e) {
                Log.e(TAG, "綁定 CameraX 生命週期發生致命錯誤", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(@NonNull ImageProxy image) {
        try {
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                image.close();
                return;
            }

            // 1. 提取 YUV_420_888 位元組資料轉換為通用 NV21
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

            // 2. 壓縮為 JPEG (設定 30% 低畫質降低藍牙頻寬壓力)
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 30, out); 
            byte[] jpegData = out.toByteArray();

            // 3. 【保留本地排查通道】已移除 UI 依賴，安全發送廣播供未來監測
            Intent localIntent = new Intent("de.rhaeus.wearsync.LOCAL_CAMERA_STREAM");
            localIntent.putExtra("JPEG_DATA", jpegData);
            localIntent.setPackage(getPackageName()); 
            sendBroadcast(localIntent);

            // 🚀 4. 【核心大升級】剔除 Base64 文本，改用 MessageClient (控制) + ChannelClient (數據流)
            new Thread(() -> {
                try {
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    for (Node n : nodes) {
                        // ① 先發一條極輕的控制信令，讓手錶端拉起介面
                        JSONObject signal = new JSONObject();
                        signal.put("sender", "phone");
                        signal.put("type", "camera_action"); 
                        signal.put("action", "START_CAMERA_UI");
                        Wearable.getMessageClient(this).sendMessage(n.getId(), UNIVERSAL_SYNC_PATH, signal.toString().getBytes(StandardCharsets.UTF_8));

                        // ② 建立高性能 Channel 位元組流通道
                        com.google.android.gms.wearable.ChannelClient.Channel channel = 
                                Tasks.await(Wearable.getChannelClient(this).openChannel(n.getId(), "/wear-camera-stream"));
                        
                        // ③ 獲取通道輸出流，直接將原始 JPEG 位元組灌進去
                        java.io.OutputStream os = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel));
                        os.write(jpegData);
                        os.flush();
                        os.close(); // 發完立即關閉流，釋放藍牙頻寬
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Channel 混合發送畫面失敗", e);
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "畫面壓縮投遞異常", e);
        } finaly {
            image.close();
        }
    }

    @Override
    public void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) cameraProvider.unbindAll();
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "相機背景採集通知",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
