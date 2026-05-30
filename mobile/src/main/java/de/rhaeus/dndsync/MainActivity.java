package de.rhaeus.dndsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🎯 核心注入：在加載任何視圖與主題之前，開啟 Material 3 隨手機桌布自動變色的超能力
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        
        super.onCreate(savedInstanceState);
        // 確保使用你定義好的全域主題
        setTheme(R.style.Theme_DNDSync); 
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            // 🎯 保持你原版完全一致的容器 ID 與 MainFragment 加載邏輯
            ft.replace(R.id.settings, new MainFragment());
            ft.commit();
        }
    }
}
