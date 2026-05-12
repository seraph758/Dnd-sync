package de.rhaeus.dndsync;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 核心测试：启动伴侣设备关联 ---
        try {
            Log.d("DNDSync_Debug", "尝试启动 CompanionDeviceManager 关联流程...");
            CompanionManager.startAssociation(this);
        } catch (Exception e) {
            Log.e("DNDSync_Debug", "CDM 关联启动失败: " + e.getMessage());
        }
        // ------------------------------
    }
}

