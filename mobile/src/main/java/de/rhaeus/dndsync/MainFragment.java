package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        dndPref = findPreference("dnd_permission_key");
        // 1. 捕捉那個原本寫死文字的連線狀態組件
        connectivityPref = findPreference("connectivity_state_key");

        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (!checkDNDPermission()) {
                        openDNDPermissionRequest();
                    } else {
                        Toast.makeText(getContext(), "DND Permission allowed", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
        }
        checkDNDPermission();

        // 2. 初始化連線監聽：讓手機主動去尋找手錶的身份暗號 "wear_dnd_sync"
        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkDNDPermission();
        // 註冊藍牙連線狀態監聽
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        // 登出藍牙連線狀態監聽，防止記憶體洩漏
        unregisterConnectivityListener();
    }

    private boolean checkDNDPermission() {
        if (getContext() == null) return false;
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager == null) return false;

        boolean allowed = mNotificationManager.isNotificationPolicyAccessGranted();
        if (dndPref != null) {
            if (allowed) {
                dndPref.setSummary(R.string.dnd_permission_allowed);
            } else {
                dndPref.setSummary(R.string.dnd_permission_not_allowed);
            }
        }
        return allowed;
    }

    private void openDNDPermissionRequest() {
        Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        startActivity(intent);
    }

    /**
     * 動態檢測手錶連線狀態
     */
    private void initConnectivityCheck() {
        if (getContext() == null) return;

        // 異步查詢當前有沒有活著的手錶節點
        Wearable.getCapabilityClient(getContext())
                .getCapability("wear_dnd_sync", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty()));

        // 當藍牙斷開或重新連上時觸發的監聽器
        capabilityChangedListener = capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty());
    }

    private void registerConnectivityListener() {
        if (getContext() != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext()).addListener(capabilityChangedListener, "wear_dnd_sync");
        }
    }

    private void unregisterConnectivityListener() {
        if (getContext() != null && capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext()).removeListener(capabilityChangedListener);
        }
    }

    // 根據有沒有找到手錶節點，即時動態刷新 XML 裡那行寫死的文字
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
