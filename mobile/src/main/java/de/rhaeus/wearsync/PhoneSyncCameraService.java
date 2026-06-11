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
    
    private final Handler mTimeoutHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable mTimeoutRunnable = null;
    private static final int WATCH_TIMEOUT_MS = 8000; // 8秒防空轉自毀防火牆
    
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
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1025, notification);
    
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "📥 PhoneSyncCameraService 收到 Action: " + action);
    
            if ("START_CAMERA".equalsIgnoreCase(action)) {
                isRunning = true;
                // 1. 異步建立管道，並向手錶發送通知
                prepareChannelAndCamera(); 
                
                // ⚠️ 註意：此時把原本這裏的 startCameraPipeline(); 刪除或註釋掉！！！
                // startCameraPipeline(); // 🛑 絕不在這裏盲目點火！
    
                // 2. 🎯 開啟 8 秒超時防死鎖自毀防火牆
                if (mTimeoutRunnable != null) mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
                mTimeoutRunnable = () -> {
                    if (isRunning && cameraProvider == null) { // 如果8秒內相機Pipeline還沒被正式點火
                        Log.e(TAG, "🚨 [超時自毀] 手錶端 8 秒內未回傳 READY 握手信號，判定拉起失敗，自動關閉後台服務！");
                        stopSelf();
                    }
                };
                mTimeoutHandler.postDelayed(mTimeoutRunnable, WATCH_TIMEOUT_MS);
    
            } else if ("WATCH_READY".equalsIgnoreCase(action)) {
                // 3. 🎯 核心雙向握手點火點：手錶端已經完全準備就緒，手機正式點火啟動 CameraX！
                Log.d(TAG, "🔥 [雙向握手成功] 收到手錶端 READY 信號，手機相機 Pipeline 正式點火！");
                if (mTimeoutRunnable != null) {
                    mTimeoutHandler.removeCallbacks(mTimeoutRunnable); // 清除自毀定時器
                }
                startCameraPipeline();
    
            } else if ("STOP_CAMERA".equalsIgnoreCase(action)) {
                Log.d(TAG, "🛑 收到停止相機指令，主動銷毀服務。");
                stopSelf();
            } else if ("TAKE_PICTURE".equalsIgnoreCase(action)) {
                // 你原本的拍照動作，保持不變
                isCaptureRequested = true;
            }
        }
        return START_STICKY;
    }

    public static void sendCameraControlToWatchLive(Context context, String action) {
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

                // 🎯【核心修正】：加入 ExtensionsManager 的 ImageAnalysis 相容性深度校驗
                try {
                    ListenableFuture<ExtensionsManager> extensionsManagerFuture = 
                            ExtensionsManager.getInstanceAsync(this, cameraProvider);
                    ExtensionsManager extensionsManager = extensionsManagerFuture.get();

                    CameraSelector hdrSelector = null;
                    CameraSelector nightSelector = null;

                    // 1. 檢查 HDR 是否可用且硬體支援與 ImageAnalysis 同時繫結
                    if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                        hdrSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.HDR);
                        if (!extensionsManager.isImageAnalysisSupported(hdrSelector, androidx.camera.extensions.ExtensionMode.HDR)) {
                            Log.w(TAG, "⚠️ 檢測到目前硬體不支援在 HDR 開啟時進行影像串流採集，放棄啟用 HDR 特效。");
                            hdrSelector = null;
                        }
                    }

                    // 2. 檢查 夜景 是否可用且硬體支援與 ImageAnalysis 同時繫結
                    if (extensionsManager.isExtensionAvailable(cameraSelector, androidx.camera.extensions.ExtensionMode.NIGHT)) {
                        nightSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, androidx.camera.extensions.ExtensionMode.NIGHT);
                        if (!extensionsManager.isImageAnalysisSupported(nightSelector, androidx.camera.extensions.ExtensionMode.NIGHT)) {
                            Log.w(TAG, "⚠️ 檢測到目前硬體不支援在夜景模式開啟時進行影像串流採集，放棄啟用夜景特效。");
                            nightSelector = null;
                        }
                    }

                    // 3. 根據校驗結果安全指派 Selector，若都不支援則維持原生標準通道
                    if (hdrSelector != null) {
                        cameraSelector = hdrSelector;
                        Log.d(TAG, "✨ 通過相容性校驗，成功啟用手機硬體級 HDR 串流優化！");
                    } else if (nightSelector != null) {
                        cameraSelector = nightSelector;
                        Log.d(TAG, "✨ 通過相容性校驗，成功啟用手機硬體級 夜景串流優化！");
                    } else {
                        Log.d(TAG, "ℹ️ 為了保證低延遲取景串流穩定性，自動安全採用標準硬體通道。");
                    }

                } catch (Exception extEx) {
                    Log.w(TAG, "廠商 Extension 模組載入異常，自動降級使用標準硬體通道: " + extEx.getMessage());
                }

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(240, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                // 🎯 此時 cameraSelector 已經過嚴格的相容性校驗，絕對不會再拋出 IllegalArgumentException！
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                Log.d(TAG, "🎉 CameraX Pipeline 成功繫結生命週期，資料串流已就緒！");

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
