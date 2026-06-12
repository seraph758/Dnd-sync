package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class PhoneCameraBridgeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        String nodeId = getIntent().getStringExtra("node_id");
        Log.d("WearSync_Bridge", "🔥 [前台權限借屍還魂] BridgeActivity 啟動，強行索要相機前台豁免權。手錶 Node: " + nodeId);
        
        Intent svc = new Intent(this, PhoneSyncCameraService.class);
        svc.setAction("START_CAMERA");
        svc.putExtra("node_id", nodeId);
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.e("WearSync_Bridge", "後台強起前台相機服務失敗", e);
        }
        
        // 瞬間功成身退
        finish();
        overridePendingTransition(0, 0); // 移除動畫，完全無感
    }
}
