package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
[span_20](start_span)import android.os.Bundle;[span_20](end_span)
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
        [span_21](start_span)setPreferencesFromResource(R.xml.root_preferences, rootKey);[span_21](end_span)

        // 1. 獲取 XML 中的組件
        [span_22](start_span)dndPref = findPreference("dnd_permission_key");[span_22](end_span)
        accPref = findPreference("acc_permission_key");
        [span_23](start_span)bedtimePref = findPreference("bedtime_key");[span_23](end_span)
        connectivityPref = findPreference("connectivity_state_key");

        // 2. 設置“勿擾訪問權限”點擊事件
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                if (!checkDNDPermission()) {
                    [span_24](start_span)Toast.makeText(getContext(), "請在手機端或通過 ADB 授予勿擾權限", Toast.LENGTH_LONG).show();[span_24](end_span)
                }
                [span_25](start_span)return true;[span_25](end_span)
            });
        }

        // 3. 設置“輔助功能權限”點擊事件（用於模擬點擊）
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                if (!checkAccessibilityService()) {
                    openAccessibility();
                }
                [span_26](start_span)return true;[span_26](end_span)
            });
        }

        // 4. 初始化連線檢測
        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        [span_27](start_span)super.onResume();[span_27](end_span)
        [span_28](start_span)checkDNDPermission();[span_28](end_span)
        [span_29](start_span)checkAccessibilityService();[span_29](end_span)
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private boolean checkAccessibilityService() {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        [span_30](start_span)boolean connected = (serv != null);[span_30](end_span)
        
        if (accPref != null) {
            [span_31](start_span)accPref.setSummary(connected ? "權限已開啟" : "未開啟，點擊去設置");[span_31](end_span)
        }
        
        if (bedtimePref != null) {
            [span_32](start_span)bedtimePref.setEnabled(connected);[span_32](end_span)
            if (!connected) {
                [span_33](start_span)bedtimePref.setChecked(false);[span_33](end_span)
            }
        }
        [span_34](start_span)return connected;[span_34](end_span)
    }

    private boolean checkDNDPermission() {
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        [span_35](start_span)boolean allowed = (mNotificationManager != null && mNotificationManager.isNotificationPolicyAccessGranted());[span_35](end_span)
        
        if (dndPref != null) {
            [span_36](start_span)dndPref.setSummary(allowed ? "權限已獲得" : "點擊檢查權限狀態");[span_36](end_span)
        }
        [span_37](start_span)return allowed;[span_37](end_span)
    }

    private void openAccessibility() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            [span_38](start_span)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);[span_38](end_span)
            startActivity(intent);
        } catch (Exception e) {
            [span_39](start_span)Toast.makeText(getContext(), "無法跳轉，請在手錶設置-輔助功能中手動開啟", Toast.LENGTH_SHORT).show();[span_39](end_span)
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
