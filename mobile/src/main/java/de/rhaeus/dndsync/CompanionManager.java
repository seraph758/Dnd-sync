package de.rhaeus.dndsync;

import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.IntentSender;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Pattern;

/**
 * 伴侣设备管理器 - 独立模块
 * 作用：将 App 注册为手表的伴侣应用，获得 Android 系统级的后台保护。
 */
public class CompanionManager {
    private static final String TAG = "DNDSync_Companion";
    private static final int SELECT_DEVICE_REQUEST_CODE = 42;

    public static void startAssociation(AppCompatActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "系统版本过低，不需要 CDM");
            return;
        }

        CompanionDeviceManager deviceManager = (CompanionDeviceManager) activity.getSystemService(Context.COMPANION_DEVICE_SERVICE);

        // 1. 定义过滤器（这里匹配所有蓝牙设备，或者可以根据三星手表的名称规律匹配）
        BluetoothLeDeviceFilter deviceFilter = new BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("Galaxy Watch.*|Watch.*", Pattern.CASE_INSENSITIVE))
                .build();

        // 2. 创建关联请求
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                .setSingleDevice(false) // 允许选择多个
                .build();

        Log.d(TAG, "开始搜寻伴侣设备...");

        // 3. 发起请求
        deviceManager.associate(request, new CompanionDeviceManager.Callback() {
            @Override
            public void onDeviceFound(IntentSender chooserLauncher) {
                try {
                    // 弹出系统对话框让用户确认哪台是你的表
                    activity.startIntentSenderForResult(chooserLauncher,
                            SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Log.e(TAG, "无法启动设备选择器", e);
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                Log.e(TAG, "关联失败: " + error);
            }
        }, null);
    }
}

