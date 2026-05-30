package de.rhaeus.dndsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 確保使用你定義好的全域主題
        setTheme(R.style.Theme_DNDSync); 
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            // 🎯 核心修正：
            // 1. 將 R.id.fragment_container 改為對齊 activity_main.xml 裡的 R.id.settings
            // 2. 將 new SettingsFragment() 改為加載你真實存在的 new MainFragment()
            ft.replace(R.id.settings, new MainFragment());
            ft.commit();
        }
    }
}
