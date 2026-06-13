package de.rhaeus.wearsync;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

public class PhoneSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_PhoneMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

// 🎯 [手機端核心防禦]：防止手機端彈出 Activity 提權後，因無人觸控自動熄屏導致相機被一加系統強行凍結
getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }

        // 🎯 處理第一次創建 Activity 時的拉起指令
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 🎯 處理 Activity 已經在後台存活時，再次被複用拉起時的指令
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "📥 MainActivity 收到意圖 Action: " + action);

        if ("ACTION_START_CAMERA_FLOW".equalsIgnoreCase(action)) {
            Log.d(TAG, "🎬 [前台合法接力] 已經處於前台活躍狀態，正在開啟相機前台服務...");
            Intent svc = new Intent(this, PhoneSyncCameraService.class);
            svc.setAction("START_CAMERA");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        }
    }
}
