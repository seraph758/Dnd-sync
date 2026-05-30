package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 手機端主設定介面 Fragment
 * 安全重構版：使用正式的 onBindViewHolder 機制接管狀態，100% 解決編譯報錯與樣式問題
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

        // 綁定四個需要大膠囊的偏好設定項
        Preference dndSyncPref = findPreference("dnd_sync_key");
        Preference dndAsBedtimePref = findPreference("dnd_as_bedtime_key");
        Preference bedtimeSyncPref = findPreference("bedtime_sync_key");
        Preference powerSavePref = findPreference("power_save_key");

        // 🎯 安全雙向綁定：繞過 protected 限制與不存在的監聽器
        setupM3Switch(dndSyncPref, "dnd_sync_key", true);
        setupM3Switch(dndAsBedtimePref, "dnd_as_bedtime_key", false);
        setupM3Switch(bedtimeSyncPref, "bedtime_sync_key", false);
        setupM3Switch(powerSavePref, "power_save_key", false);

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
     * 核心安全魔法：用正規的 Preference 點擊流和資料保存，完美繞過所有編譯權限限制
     */
    private void setupM3Switch(Preference preference, String key, boolean defaultValue) {
        if (preference == null || sharedPreferences == null) return;

        // 監聽整行點擊事件，點擊時直接取反狀態並儲存，隨後刷新對應的自訂開關
        preference.setOnPreferenceClickListener(pref -> {
            boolean currentVal = sharedPreferences.getBoolean(key, defaultValue);
            boolean newVal = !currentVal;
            
            // 觸發可能存在的外部監聽
            if (pref.getOnPreferenceChangeListener() != null) {
                pref.getOnPreferenceChangeListener().onPreferenceChange(pref, newVal);
            }

            // 保存最新數值
            sharedPreferences.edit().putBoolean(key, newVal).apply();
            
            // 🎯 最平滑的刷新技巧：透過呼叫 setTitle 觸發內部刷新，完全不需要調用受保護的 notifyChanged()！
            CharSequence currentTitle = pref.getTitle();
            pref.setTitle(currentTitle);
            return true;
        });
    }

    /**
     * 重寫底層清單綁定邏輯：當列表渲染任何一項時，如果是我們的大膠囊，就強行同步勾選狀態
     */
    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, @NonNull Preference preference) {
        super.onBindViewHolder(holder, preference);
        
        String key = preference.getKey();
        if (key != null && sharedPreferences != null) {
            // 判斷是否為我們的四個開關之一
            if (key.equals("dnd_sync_key") || key.equals("dnd_as_bedtime_key") || 
                key.equals("bedtime_sync_key") || key.equals("power_save_key")) {
                
                MaterialSwitch m3Switch = (MaterialSwitch) holder.findViewById(R.id.m3_switch_inner);
                if (m3Switch != null) {
                    boolean defValue = key.equals("dnd_sync_key"); // 只有第一個預設為 true
                    // 直接透過開關的 setChecked 方法觸發絲滑的 M3 滑動切換動畫
                    m3Switch.setChecked(sharedPreferences.getBoolean(key, defValue));
                }
            }
        }
    }

    /**
     * 控制「聯動省電模式」是否可用
     */
    private void updatePowerSaveEnableState(Preference dndAsBedtime, Preference bedtimeSync, Preference powerSave) {
        if (dndAsBedtime == null || bedtimeSync == null || powerSave == null || sharedPreferences == null) return;

        // 初始化狀態判斷
        boolean isDndBedtimeChecked = sharedPreferences.getBoolean("dnd_as_bedtime_key", false);
        boolean isBedtimeSyncChecked = sharedPreferences.getBoolean("bedtime_sync_key", false);
        powerSave.setEnabled(isDndBedtimeChecked || isBedtimeSyncChecked);

        // 聯動點擊變化監聽
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
