package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat; // 🎯 換回原生的相容類型

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 手機端主設定介面 Fragment
 * 終極修正版：對齊 SwitchPreferenceCompat，徹底消滅 ClassNotFoundException 與空指標閃退
 */
public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // 從 XML 資源檔案加載設定佈局
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 徹底關閉內建的舊版隱藏標題，防止重疊
        if (getPreferenceScreen() != null) {
            getPreferenceScreen().setTitle(null);
        }

        // 綁定基礎控制項
        dndPref = findPreference("dnd_permission_key");
        connectivityPref = findPreference("connectivity_state_key");

        // 🎯 完美對齊：使用 SwitchPreferenceCompat 安全接收
        SwitchPreferenceCompat dndAsBedtime = findPreference("dnd_as_bedtime_key");
        SwitchPreferenceCompat bedtimeSync = findPreference("bedtime_sync_key");
        SwitchPreferenceCompat powerSave = findPreference("power_save_key");

        // 安全監聽防護
        if (dndAsBedtime != null && bedtimeSync != null && powerSave != null) {
            if (dndAsBedtime.isChecked() || bedtimeSync.isChecked()) {
                powerSave.setEnabled(true);
            }

            dndAsBedtime.setOnPreferenceChangeListener((preference, newValue) -> {
                if ((boolean) newValue) {
                    powerSave.setEnabled(true);
                } else if (!bedtimeSync.isChecked()) {
                    powerSave.setEnabled(false);
                }
                return true;
            });

            bedtimeSync.setOnPreferenceChangeListener((preference, newValue) -> {
                if ((boolean) newValue) {
                    powerSave.setEnabled(true);
                } else if (!dndAsBedtime.isChecked()) {
                    powerSave.setEnabled(false);
                }
                return true;
            });
        }

        // 配置勿擾權限點擊事件
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                if (!checkDNDPermission()) {
                    openDNDPermissionRequest();
                } else {
                    Toast.makeText(getContext(), "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        
        checkDNDPermission();
        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkDNDPermission(); 
        registerConnectivityListener(); 
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener(); 
    }

    private boolean checkDNDPermission() {
        if (getContext() == null) return false;
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager == null) return false;

        boolean allowed = mNotificationManager.isNotificationPolicyAccessGranted();
        if (dndPref != null) {
            if (allowed) {
                dndPref.setSummary("DND access granted");
            } else {
                dndPref.setSummary("DND access denied");
            }
        }
        return allowed;
    }

    private void openDNDPermissionRequest() {
        Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        startActivity(intent);
    }

    private void initConnectivityCheck() {
        if (getContext() == null) return;

        Wearable.getCapabilityClient(getContext())
                .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty()));

        capabilityChangedListener = capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty());
    }

    private void registerConnectivityListener() {
        if (getContext() != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext()).addListener(capabilityChangedListener, "dnd_sync");
        }
    }

    private void unregisterConnectivityListener() {
        if (getContext() != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext()).removeListener(capabilityChangedListener);
        }
    }

    private void updateConnectionUI(boolean isConnected) {
        if (connectivityPref != null) {
            if (isConnected) {
                connectivityPref.setSummary("已成功連線到手錶");
            } else {
                connectivityPref.setSummary("未發現配對手錶，請檢查藍牙或 Wear OS App");
            }
        }
    }
}
