package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Wearable;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference accPref;
    private Preference connectivityPref;
    private SwitchPreferenceCompat bedtimePref;

    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 1. 獲取 XML 中的組件
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");
        bedtimePref = findPreference("bedtime_key");
        connectivityPref = findPreference("connectivity_state_key");

        // 2. 設置“勿擾訪問權限”點擊事件
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                if (!checkDNDPermission()) {
                    Toast.makeText(getContext(), "請在手機端或通過 ADB 授予勿擾權限", Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }

        // 3. 設置“輔助功能權限”點擊事件（用於模擬點擊）
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                if (!checkAccessibilityService()) {
                    openAccessibility();
                }
                return true;
            });
        }

        // 4. 初始化連線檢測
        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkDNDPermission();
        checkAccessibilityService();
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private boolean checkAccessibilityService() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        boolean connected = (serv != null);

        if (accPref != null) {
            accPref.setSummary(connected ? "權限已開啟" : "未開啟，點擊去設置");
        }

        if (bedtimePref != null) {
            bedtimePref.setEnabled(connected);
            if (!connected) {
                bedtimePref.setChecked(false);
            }
        }
        return connected;
    }

    private boolean checkDNDPermission() {
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        boolean allowed = (mNotificationManager != null && mNotificationManager.isNotificationPolicyAccessGranted());

        if (dndPref != null) {
            dndPref.setSummary(allowed ? "權限已獲得" : "點擊檢查權限狀態");
        }
        return allowed;
    }

    private void openAccessibility() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "無法跳轉，請在手錶設置-輔助功能中手動開啟", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 初始化手機連線狀態查詢
     */
    private void initConnectivityCheck() {
        if (getContext() == null) return;

        // 手錶端去尋找通訊通道（此處的 "dnd_sync" 需與手機端 Manifest 的特定 Capability 宣告一致）
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
                connectivityPref.setSummary("已成功連線到手機");
            } else {
                connectivityPref.setSummary("未發現配對手機，請檢查藍牙");
            }
        }
    }
}
