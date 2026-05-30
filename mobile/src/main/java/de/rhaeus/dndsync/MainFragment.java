package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 手機端主設定介面 Fragment
 * 終極美化版：通過自定義 Widget 手動接管 M3 大膠囊狀態，徹底擊碎舊 Preference 庫的頑固渲染
 */
public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;
    private SharedPreferences sharedPreferences;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        if (getContext() != null) {
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        }

        if (getPreferenceScreen() != null) {
            getPreferenceScreen().setTitle(null);
        }

        dndPref = findPreference("dnd_permission_key");
        connectivityPref = findPreference("connectivity_state_key");

        // 綁定四個大膠囊偏好設定
        Preference dndSyncPref = findPreference("dnd_sync_key");
        Preference dndAsBedtimePref = findPreference("dnd_as_bedtime_key");
        Preference bedtimeSyncPref = findPreference("bedtime_sync_key");
        Preference powerSavePref = findPreference("power_save_key");

        // 🎯 手動初始化與監聽狀態
        initM3Switch(dndSyncPref, "dnd_sync_key", true);
        initM3Switch(dndAsBedtimePref, "dnd_as_bedtime_key", false);
        initM3Switch(bedtimeSyncPref, "bedtime_sync_key", false);
        initM3Switch(powerSavePref, "power_save_key", false);

        // 處理聯動省電模式的 Enabled 狀態
        updatePowerSaveEnableState(dndAsBedtimePref, bedtimeSyncPref, powerSavePref);

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

    /**
     * 核心魔法：手動將 SharedPreferences 與自訂的 MaterialSwitch 大膠囊進行雙向綁定
     */
    private void initM3Switch(Preference preference, String key, boolean defaultValue) {
        if (preference == null || sharedPreferences == null) return;

        // 當清單項加載出來時，手動去勾選大膠囊的狀態
        preference.setOnPreferenceBindListener(holder -> {
            MaterialSwitch m3Switch = (MaterialSwitch) holder.findViewById(R.id.m3_switch_inner);
            if (m3Switch != null) {
                m3Switch.setChecked(sharedPreferences.getBoolean(key, defaultValue));
            }
        });

        // 監聽整行點擊事件，點擊時切換狀態，並觸發絲滑動畫
        preference.setOnPreferenceClickListener(pref -> {
            boolean currentVal = sharedPreferences.getBoolean(key, defaultValue);
            boolean newVal = !currentVal;
            
            // 觸發監聽器確認是否允許修改（供後續邏輯控制）
            if (pref.getOnPreferenceChangeListener() != null) {
                pref.getOnPreferenceChangeListener().onPreferenceChange(pref, newVal);
            }

            // 保存狀態並刷新 UI
            sharedPreferences.edit().putBoolean(key, newVal).apply();
            preference.notifyChanged();
            return true;
        });
    }

    /**
     * 控制「聯動省電模式」是否可用
     */
    private void updatePowerSaveEnableState(Preference dndAsBedtime, Preference bedtimeSync, Preference powerSave) {
        if (dndAsBedtime == null || bedtimeSync == null || powerSave == null || sharedPreferences == null) return;

        // 初始化判斷
        boolean isDndBedtimeChecked = sharedPreferences.getBoolean("dnd_as_bedtime_key", false);
        boolean isBedtimeSyncChecked = sharedPreferences.getBoolean("bedtime_sync_key", false);
        powerSave.setEnabled(isDndBedtimeChecked || isBedtimeSyncChecked);

        // 聯動監聽
        dndAsBedtime.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean newVal = (boolean) newValue;
            boolean currentBedtimeSync = sharedPreferences.getBoolean("bedtime_sync_key", false);
            powerSave.setEnabled(newVal || currentBedtimeSync);
            return true;
        });

        bedtimeSync.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean newVal = (boolean) newValue;
            boolean currentDndBedtime = sharedPreferences.getBoolean("dnd_as_bedtime_key", false);
            powerSave.setEnabled(newVal || currentDndBedtime);
            return true;
        });
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
