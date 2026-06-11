package de.rhaeus.wearsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

public class PhoneCameraBridgeActivity extends Activity {
    private static final String TAG = "WearSync_Bridge";
    
    // 🎯 痛點二修復：等待後台 Service 安全頂起通知後再執行 finish()，延長前台豁免時間保護線程
    private final BroadcastReceiver serviceReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("de.rhaeus.wearsync.ACTION_SERVICE_READY".equals(intent.getAction())) {
                Log.d(TAG, "🎯 收到 FGS 註冊成功廣播，Service 已在前台安全著陸，BridgeActivity 退出。");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "🚀 [前台豁免過渡] BridgeActivity 已點火，開始為後台相機注入前台令牌...");

        // 註冊安全廣播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReadyReceiver, new IntentFilter("de.rhaeus.wearsync.ACTION_SERVICE_READY"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReadyReceiver, new IntentFilter("de.rhaeus.wearsync.ACTION_SERVICE_READY"));
        }

        Intent svc = new Intent(this, PhoneSyncCameraService.class);
        svc.setAction("START_CAMERA");
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "啟動相機服務失敗", e);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(serviceReadyReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
