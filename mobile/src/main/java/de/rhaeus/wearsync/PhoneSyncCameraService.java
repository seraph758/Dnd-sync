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
    // 🎯 拍照旗標：點拍照時手錶發 TAKE_PICTURE 指令，只把這個開關打開，動作和以前完全一樣
    private volatile boolean mShouldCaptureNextFrame = false; 

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "🎬 Service 收到指令: " + action);

        // 1️⃣ 拍照分流：只設置旗標，絕不驚動原本穩定的生命週期
        if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
            Log.d(TAG, "📸 收到拍照信號，將在下一影格執行捕獲...");
            mShouldCaptureNextFrame = true;
            return START_NOT_STICKY; 
        }

        // 2️⃣ 關閉指令
        if ("STOP_CAMERA".equalsIgnoreCase(action)) {
            Log.d(TAG, "🛑 執行主動關閉：安全釋放相機與管道");
            isRunning = false;
            sendCameraControlToWatchLive(this, "STOP_CAMERA"); 
            if (cameraProvider != null) cameraProvider.unbindAll();
            closeChannelSafely();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 3️⃣ 完美還原：當時成功的啟動與雙端握手（WATCH_READY）點火核心
        if ("START_CAMERA".equalsIgnoreCase(action) || "WATCH_READY".equalsIgnoreCase(action)) {
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
                Log.d(TAG, "✅ 成功掛載前台相機通知");
            } catch (Exception e) {
                Log.e(TAG, "🚨 startForeground 失敗", e);
                stopSelf();
                return START_NOT_STICKY;
            }

            isRunning = true;
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            
            // 握手成功後直接建立 Channel
            setupActiveChannel();
        }

        return START_NOT_STICKY;
    }

    private void setupActiveChannel() {
        mStreamExecutor.execute(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                if (nodes.isEmpty()) {
                    Log.e(TAG, "❌ 未發現可用手錶節點");
                    return;
                }
                String nodeId = nodes.get(0).getId();
                mActiveChannel = Tasks.await(Wearable.getChannelClient(this).openChannel(nodeId, "/wear-camera-stream"));
                mChannelOutputStream = Tasks.await(Wearable.getChannelClient(this).getOutputStream(mActiveChannel));
                Log.d(TAG, "🚀 [/wear-camera-stream] 藍牙長連接管道建立成功");

                new Handler(Looper.getMainLooper()).post(this::startCameraXDataStream);
            } catch (Exception e) {
                Log.e(TAG, "🚨 建立通道失敗", e);
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
                Log.d(TAG, "⚙️ CameraX 採集核心綁定成功");
            } catch (Exception e) {
                Log.e(TAG, "初始化 CameraX 失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrameAndStream(@NonNull ImageProxy image) {
        if (!isRunning || mChannelOutputStream == null) {
            image.close();
            return;
        }

        try {
            // 🎯 完美還原：當初成功時的「盲讀轉換邏輯」，確保手錶預覽清晰不花屏！
  // ✅ 改用最穩固的手工位移，強制以大端序寫入 4 位元組長度頭
byte[] header = new byte[4];
int len = jpegData.length;
header[0] = (byte) ((len >> 24) & 0xFF);
header[1] = (byte) ((len >> 16) & 0xFF);
header[2] = (byte) ((len >> 8) & 0xFF);
header[3] = (byte) (len & 0xFF);

mChannelOutputStream.write(header);
mChannelOutputStream.write(jpegData);
mChannelOutputStream.flush();


                // 🎯 拍照動作：與以前完全相同，只是在寫入完成後多了一步廣播通知
                if (mShouldCaptureNextFrame) {
                    mShouldCaptureNextFrame = false;
                    Log.d(TAG, "📸 捕捉影格成功，正在寫入手機相簿...");
                    saveToGalleryInternal(jpegData);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "數據傳輸失敗", e);
        } finally {
            image.close();
        }
    }

    // 🎯 完美還原：歷史成功的 YUV 盲讀轉換函數
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
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 85, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // 🎯 僅在當初寫入磁碟的程式碼結尾，定點補上 MediaScanner 廣播，通知一加相簿刷新
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
                        Log.d(TAG, "🎯 照片二進位字節已成功寫入儲存區。");

                        // 🔥 僅在此處定點修復：向系統媒體庫發送廣播。當初相簿沒反應就是少了他
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(itemUri);
                        sendBroadcast(mediaScanIntent);
                        Log.d(TAG, "📢 已成功向系統廣播刷新媒體庫，相簿此時會顯示照片！");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "寫入手機相簿發生異常", e);
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
                }
            } catch (Exception e) {
                Log.e(TAG, "通訊失敗", e);
            }
        }).start();
    }

    private void closeChannelSafely() {
        try {
            if (mChannelOutputStream != null) { mChannelOutputStream.close(); mChannelOutputStream = null; }
            if (mActiveChannel != null) { Wearable.getChannelClient(this).close(mActiveChannel); mActiveChannel = null; }
        } catch (Exception e) { Log.e(TAG, "關閉通道失敗", e); }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraProvider != null) cameraProvider.unbindAll();
        mStreamExecutor.shutdownNow();
        closeChannelSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "相機背景採集通知", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
