package de.rhaeus.dndsync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

public class CameraService extends Service implements LifecycleOwner {
    private static final String TAG = "CameraService";
    private static final String CAMERA_STREAM_PATH = "/camera-stream";
    private static final int CHUNK_SIZE = 4096; // 每块数据大小
    private static final int JPEG_QUALITY = 50; // JPEG 压缩质量

    private LifecycleRegistry lifecycleRegistry;
    private ExecutorService executorService;
    private ProcessCameraProvider cameraProvider;
    private boolean isStreaming = false;
    private ImageAnalysis imageAnalysis;

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        Log.d(TAG, "CameraService 已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START_CAMERA".equals(action)) {
                startCameraStream();
            } else if ("STOP_CAMERA".equals(action)) {
                stopCameraStream();
            } else if ("TAKE_PHOTO".equals(action)) {
                captureAndSendPhoto();
            }
        }
        return START_STICKY;
    }

    /**
     * 启动相机流
     */
    private void startCameraStream() {
        if (isStreaming) {
            Log.d(TAG, "相机已在运行");
            return;
        }
        
        executorService = Executors.newSingleThreadExecutor();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        isStreaming = true;
        
        Log.d(TAG, "✅ 相机服务已启动");
    }

    /**
     * 停止相机流
     */
    private void stopCameraStream() {
        isStreaming = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "解绑相机失败", e);
            }
        }
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        
        Log.d(TAG, "✅ 相机服务已停止");
    }

    /**
     * 拍照并发送到手表
     */
    private void captureAndSendPhoto() {
        if (!isStreaming) {
            Log.d(TAG, "❌ 相机未运行，无法拍照");
            return;
        }

        new Thread(() -> {
            try {
                ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                        ProcessCameraProvider.getInstance(this);
                
                cameraProviderFuture.addListener(() -> {
                    try {
                        ProcessCameraProvider provider = cameraProviderFuture.get();
                        this.cameraProvider = provider;

                        // 创建图像分析用例来捕获单帧
                        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                        final boolean[] capturedFrame = {false};
                        imageAnalysis.setAnalyzer(executorService, imageProxy -> {
                            if (!capturedFrame[0]) {
                                capturedFrame[0] = true;
                                captureFrame(imageProxy);
                            }
                            imageProxy.close();
                        });

                        try {
                            provider.unbindAll();
                        } catch (Exception e) {
                            Log.d(TAG, "解绑异常（正常）");
                        }

                        CameraSelector cameraSelector = new CameraSelector.Builder()
                                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                .build();

                        Camera camera = provider.bindToLifecycle(
                                this,
                                cameraSelector,
                                imageAnalysis
                        );

                        Log.d(TAG, "📸 相机已绑定，开始拍照");
                    } catch (Exception e) {
                        Log.e(TAG, "❌ 相机初始化失败", e);
                    }
                }, ContextCompat.getMainExecutor(this));
                
            } catch (Exception e) {
                Log.e(TAG, "❌ 拍照异常", e);
            }
        }).start();
    }

    /**
     * 捕获单帧并转换为JPEG发送
     */
    private void captureFrame(ImageProxy imageProxy) {
        try {
            // 获取YUV数据并转换为Bitmap
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            int pixelStride = planes[1].getPixelStride();
            
            // 确保是NV21格式
            if (imageProxy.getFormat() != ImageFormat.NV21 && 
                imageProxy.getFormat() != ImageFormat.YUV_420_888) {
                Log.w(TAG, "⚠️ 不支持的图像格式: " + imageProxy.getFormat());
                return;
            }

            // 获取 Y 平面
            ByteBuffer yBuffer = planes[0].getBuffer();
            byte[] yData = new byte[yBuffer.remaining()];
            yBuffer.get(yData);

            // 获取 U 和 V 平面
            ByteBuffer uvBuffer1 = planes[1].getBuffer();
            ByteBuffer uvBuffer2 = planes[2].getBuffer();
            int uvPixelStride = planes[1].getPixelStride();
            
            byte[] nv21 = new byte[yData.length + (yData.length >> 1)];
            System.arraycopy(yData, 0, nv21, 0, yData.length);

            if (pixelStride == 1) {
                byte[] uvData = new byte[uvBuffer1.remaining()];
                uvBuffer1.get(uvData);
                int uvSize = uvData.length;
                System.arraycopy(uvData, 0, nv21, yData.length, uvSize);
            }

            // 转换为 Bitmap
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, outputStream);
            byte[] jpegData = outputStream.toByteArray();

            Log.d(TAG, "📸 拍照成功: " + jpegData.length + " bytes");
            
            // 发送到手表
            sendImageDataToWatch(jpegData);

        } catch (Exception e) {
            Log.e(TAG, "❌ 图像捕获失败", e);
        }
    }

    /**
     * 将图像数据分块发送到手表
     */
    private void sendImageDataToWatch(byte[] imageData) {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                
                for (Node node : nodes) {
                    // 发送起始信号：0xFF + 高字节 + 低字节
                    int size = imageData.length;
                    byte[] startSignal = new byte[]{
                            (byte) 0xFF, 
                            (byte) ((size >> 8) & 0xFF), 
                            (byte) (size & 0xFF)
                    };
                    Wearable.getMessageClient(this).sendMessage(
                            node.getId(), 
                            CAMERA_STREAM_PATH, 
                            startSignal
                    );
                    
                    Log.d(TAG, "📤 发送起始信号，图像大小: " + size);
                    
                    // 分块发送数据
                    for (int i = 0; i < imageData.length; i += CHUNK_SIZE) {
                        int chunkLen = Math.min(CHUNK_SIZE, imageData.length - i);
                        byte[] chunk = new byte[chunkLen];
                        System.arraycopy(imageData, i, chunk, 0, chunkLen);
                        
                        Wearable.getMessageClient(this).sendMessage(
                                node.getId(), 
                                CAMERA_STREAM_PATH, 
                                chunk
                        );
                        
                        if ((i / CHUNK_SIZE + 1) % 10 == 0) {
                            Log.d(TAG, "📤 已发送 " + (i + chunkLen) + "/" + imageData.length + " bytes");
                        }
                    }
                    
                    Log.d(TAG, "✅ 图像已完整发送到手表");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ 发送图像数据失败", e);
            }
        }).start();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCameraStream();
        super.onDestroy();
    }
}
