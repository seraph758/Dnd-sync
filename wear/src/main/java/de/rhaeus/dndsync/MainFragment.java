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
    
    // 对应 XML 中的 Key
    private SwitchPreferenceCompat restoreAodPref;
    private SwitchPreferenceCompat restoreWakePref;
    private SwitchPreferenceCompat restoreTouchPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 1. 正确赋值成员变量（注意：不要在前面加 Preference 声明）
        dndPref = findPreference("dnd_permission_key");
        bedtimePref = findPreference("bedtime_key");
        restoreAodPref = findPreference("restore_aod_key");
        restoreWakePref = findPreference("restore_wake_key");
        restoreTouchPref = findPreference("restore_touch_key");

        // 2. 绑定权限点击
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                if (!checkDNDPermission()) {
                    Toast.makeText(getContext(), "请通过 ADB 授予必要权限！", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 3. 手动监听组合拳开关，用于即时控制下方选项的可用状态
        if (bedtimePref != null) {
            bedtimePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean isChecked = (boolean) newValue;
                toggleRestorePrefs(isChecked);
                return true;
            });
        }

        // 初始化状态检查
        checkDNDPermission();
        if (bedtimePref != null) {
            toggleRestorePrefs(bedtimePref.isChecked());
        }
    }

    // 统一控制下游选项状态
    private void toggleRestorePrefs(boolean enabled) {
        if (restoreAodPref != null) restoreAodPref.setEnabled(enabled);
        if (restoreWakePref != null) restoreWakePref.setEnabled(enabled);
        if (restoreTouchPref != null) restoreTouchPref.setEnabled(enabled);
    }

    private boolean checkDNDPermission() {
        if (getContext() == null || dndPref == null) return false;
        
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
