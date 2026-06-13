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
        if (intent != null && "ACTION_START_CAMERA_FLOW".equals(intent.getAction())) {
            String cameraAction = intent.getStringExtra("camera_action");
            Log.d(TAG, "🚀 手機主 UI 已位於前台，合法拉起相機 FGS 服務...");
            Intent serviceIntent = new Intent(this, PhoneSyncCameraService.class);
            serviceIntent.setAction(cameraAction);
            ContextCompat.startForegroundService(this, serviceIntent);
        }
    }
}
