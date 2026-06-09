package de.rhaeus.wearsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import de.rhaeus.wearsync.R; // 👈 🎯 明確加上這行，告訴編譯器 R 在哪裡

public class PhoneSyncMainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全面相容 Android 12+ 系統動態配色調色盤
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide(); // 徹底隱藏頂部 ActionBar
        }

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            // 🎯 這裡直接調用對齊後的新類名
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); 
            ft.commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}
