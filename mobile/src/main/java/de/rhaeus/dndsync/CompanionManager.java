package de.rhaeus.dndsync;

import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter; // 核心：使用经典蓝牙过滤器
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.IntentSender;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 伴侣设备管理器 - 独立模块
 * 作用：将 App 注册为手表的伴侣应用，获得 Android 系统级的后台保护。
 */
public class CompanionManager {
    private static final String TAG = "DNDSync_Companion";
    private static final int SELECT_DEVICE_REQUEST_CODE = 42;

    public static void startAssociation(AppCompatActivity activity) {
        // 只有 Android 8.0 (API 26) 以上才支持 CDM
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        CompanionDeviceManager deviceManager = (CompanionDeviceManager) activity.getSystemService(Context.COMPANION_DEVICE_SERVICE);

        // 1. 使用经典蓝牙过滤器，且不设任何限制 (这样能搜到已配对的三星手表)
        BluetoothDeviceFilter deviceFilter = new BluetoothDeviceFilter.Builder()
                .build(); 

        // 2. 创建关联请求
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                .setSingleDevice(false) // 设置为 false 更有利于在列表里找到你的设备
                .build();

        Log.d(TAG, "开始全域搜索所有蓝牙设备...");

        // 3. 发起关联
        deviceManager.associate(request, new CompanionDeviceManager.Callback() {
            @Override
            public void onDeviceFound(IntentSender chooserLauncher) {
                try {
                    // 弹出系统对话框
                    activity.startIntentSenderForResult(chooserLauncher,
                            SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Log.e(TAG, "启动设备选择器失败", e);
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                // 💡 重点观察：如果依然搜不到，Logcat 里会打印具体的错误原因
                Log.e(TAG, "CDM 关联失败: " + error);
            }
        }, null);
    }
}
