package de.rhaeus.wearsync;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentTransaction;

public class PhoneSyncMainActivity extends AppCompatActivity {
    private static final String TAG = "WearSync_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && "ACTION_SHOW_CAMERA_UI".equals(intent.getAction())) {
            Log.d(TAG, "🚀 手機前台 UI 激活就緒，名正言順啟動相機前台服務...");
            Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
            serviceIntent.setAction("START_CAMERA");
            ContextCompat.startForegroundService(this, serviceIntent);
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "🏁 手機主 UI 退出，執行聯動清理，徹底關閉相機服務...");
        Intent stopIntent = new Intent(this, PhoneSyncCameraService.class);
        stopIntent.setAction("STOP_CAMERA");
        startService(stopIntent);
        super.onDestroy();
    }

    @Override
    protected void onResume() { super.onResume(); }

    @Override
    protected void onPause() { super.onPause(); }
}
