package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class PhoneCameraBridgeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("WearSync_Bridge", "🚀 [前台豁免過渡] BridgeActivity 已啟動，成功幫 Service 拿到前台相機權限");
        
        Intent svc = new Intent(this, PhoneSyncCameraService.class);
        svc.setAction("START_CAMERA");
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.e("WearSync_Bridge", "啟動相機服務失敗", e);
        }
        
        // 瞬間功成身退，不留痕跡
        finish();
    }
}
