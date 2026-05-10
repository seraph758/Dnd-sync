package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference accPref;
    private SwitchPreferenceCompat bedtimePref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 1. 获取 XML 中的组件
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");
        bedtimePref = findPreference("bedtime_key");

        // 2. 设置“勿扰访问权限”点击事件
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                if (!checkDNDPermission()) {
                    // 提示：Wear OS 某些型号不支持直接跳转，需引导用户
                    Toast.makeText(getContext(), "请在手机端或通过ADB授予勿扰权限", Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }

        // 3. 设置“辅助功能权限”点击事件（用于模拟点击）
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                if (!checkAccessibilityService()) {
                    openAccessibility();
                }
                return true;
            });
        }
    }

    // 4. 关键：从设置界面返回时，自动刷新权限状态显示
    @Override
    public void onResume() {
        super.onResume();
        checkDNDPermission();
        checkAccessibilityService();
    }

    /**
     * 检查并更新辅助功能权限状态
     */
    private boolean checkAccessibilityService() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        boolean connected = (serv != null);
        
        if (accPref != null) {
            accPref.setSummary(connected ? "权限已开启" : "未开启，点击去设置");
        }
        
        // 如果辅助功能没开，强制禁用“组合拳/就寝模式”开关，避免逻辑错误
        if (bedtimePref != null) {
            bedtimePref.setEnabled(connected);
            if (!connected) {
                bedtimePref.setChecked(false);
            }
        }
        return connected;
    }

    /**
     * 检查并更新勿扰访问权限状态
     */
    private boolean checkDNDPermission() {
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        boolean allowed = (mNotificationManager != null && mNotificationManager.isNotificationPolicyAccessGranted());
        
        if (dndPref != null) {
            dndPref.setSummary(allowed ? "权限已获得" : "点击检查权限状态");
        }
        return allowed;
    }

    /**
     * 跳转到系统辅助功能设置页面
     */
    private void openAccessibility() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "无法跳转，请在手表设置-辅助功能中手动开启", Toast.LENGTH_SHORT).show();
        }
    }
}
