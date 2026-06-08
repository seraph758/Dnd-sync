package de.rhaeus.wearsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import de.rhaeus.wearsync.PhoneSyncMainFragment;

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

        // 🚀 修正：恢復最核心的主介面 Fragment 加載邏輯，並對齊新類名 PhoneSyncMainFragment
        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.settings, new PhoneSyncMainFragment()); // 🎯 確保這裡使用的是您改名後的 PhoneSyncMainFragment
            ft.commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 🎯 預覽小窗已安全移除，不再在此處註冊本地相機流廣播
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 🎯 預覽小窗已安全移除，不再在此處註銷本地相機流廣播
    }
}
