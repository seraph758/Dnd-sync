package de.rhaeus.dndsync;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🎯 強制啟動 Listener Service（用於診斷）
        Log.d("DNDSyncDebug", "MainActivity onCreate - 強制啟動 DNDSyncListenerService");
        try {
            startService(new Intent(this, DNDSyncListenerService.class));
        } catch (Exception e) {
            Log.e("DNDSyncDebug", "啟動 Listener 失敗", e);
        }

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new MainFragment())
                    .commit();
        }
    }
}