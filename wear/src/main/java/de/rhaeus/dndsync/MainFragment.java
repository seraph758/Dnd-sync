package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private SwitchPreferenceCompat bedtimePref;
    
    // 新增：恢复选项的 UI 变量
    private SwitchPreferenceCompat restoreAodPref;
    private SwitchPreferenceCompat restoreWakePref;
    private SwitchPreferenceCompat restoreTouchPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        dndPref = findPreference("dnd_permission_key");
        bedtimePref = (SwitchPreferenceCompat) findPreference("bedtime_key");
        
        // 绑定新增的恢复选项开关
        restoreAodPref = findPreference("restore_aod_key");
        restoreWakePref = findPreference("restore_wake_key");
        restoreTouchPref = findPreference("restore_touch_key");

        // 移除所有关于 accPref (无障碍) 的初始化代码

        dndPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                if (!checkDNDPermission()) {
                    Toast.makeText(getContext(), "请通过 ADB 授予必要权限！", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        // 当“组合拳模式”总开关关闭时，禁用细分恢复选项
        bedtimePref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            toggleRestorePrefs(enabled);
            return true;
        });

        checkDNDPermission();
        toggleRestorePrefs(bedtimePref.isChecked());
    }

    private void toggleRestorePrefs(boolean enabled) {
        if (restoreAodPref != null) restoreAodPref.setEnabled(enabled);
        if (restoreWakePref != null) restoreWakePref.setEnabled(enabled);
        if (restoreTouchPref != null) restoreTouchPref.setEnabled(enabled);
    }

    private boolean checkDNDPermission() {
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        boolean allowed = mNotificationManager.isNotificationPolicyAccessGranted();
        if (allowed) {
            dndPref.setSummary(R.string.dnd_permission_allowed);
        } else {
            dndPref.setSummary(R.string.dnd_permission_not_allowed);
        }
        return allowed;
    }
}
