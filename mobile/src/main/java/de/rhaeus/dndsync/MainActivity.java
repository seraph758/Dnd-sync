package de.rhaeus.dndsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

// 🎯 临时同显引入的包，以后去除时可连同这些 import 一起删掉
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity {

    // 🎯 临时排查组件声明
    private ImageView ivLocalPreview;

    // 🎯 临时广播接收器：精准单次接收 CameraService 传过来的局部 JPEG 字节流
    private final BroadcastReceiver localCameraStreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ivLocalPreview != null) {
                byte[] jpegData = intent.getByteArrayExtra("JPEG_DATA");
                if (jpegData != null) {
                    // 将存在于内存中的图像字节直接还原为 Bitmap 结构体
                    Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                    if (bitmap != null) {
                        // 令右下角的预览区域可见，并完成图像渲染
                        ivLocalPreview.setVisibility(View.VISIBLE);
                        ivLocalPreview.setImageBitmap(bitmap);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全面相容 Android 12+ 系統動態配色調色盤
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide(); // 徹底隱藏頂部 ActionBar
        }

        setContentView(R.layout.activity_main);

        // 🎯 绑定临时排查黑屏的布局组件
        ivLocalPreview = findViewById(R.id.iv_local_preview);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new MainFragment());
            ft.commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 🎯 动态注册本地安全相机流监听
        IntentFilter filter = new IntentFilter("de.rhaeus.dndsync.LOCAL_CAMERA_STREAM");
        
        // 严格遵循 Android 14 安全规则：设置为内部私有广播，外部应用无权探听
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localCameraStreamReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(localCameraStreamReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 🎯 及时注销，释放广播监听器资源
        try {
            unregisterReceiver(localCameraStreamReceiver);
        } catch (Exception ignored) {}
        
        // 退出或切走时，立即隐藏小窗口并清空引用，对系统资源零占用
        if (ivLocalPreview != null) {
            ivLocalPreview.setVisibility(View.GONE);
        }
    }
}
