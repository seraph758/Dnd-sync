package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Wearable;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        [span_3](start_span)setPreferencesFromResource(R.xml.root_preferences, rootKey);[span_3](end_span)

        // 1. 初始化權限與連線狀態組件
        [span_4](start_span)dndPref = findPreference("dnd_permission_key");[span_4](end_span)
        connectivityPref = findPreference("connectivity_state_key");

        assert(dndPref != null);

        // 2. 設置勿擾權限點擊事件
        dndPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                if (!checkDNDPermission()) {
                    openDNDPermissionRequest();
                } else {
                    [span_5](start_span)Toast.makeText(getContext(), "DND Permission allowed", Toast.LENGTH_SHORT).show();[span_5](end_span)
                }
                return true;
            }
        });

        // 3. 初始化雙端連接檢測
        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        [span_6](start_span)super.onResume();[span_6](end_span)
        [span_7](start_span)[span_8](start_span)checkDNDPermission();[span_7](end_span)[span_8](end_span)
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private boolean checkDNDPermission() {
        NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        [span_9](start_span)boolean allowed = mNotificationManager.isNotificationPolicyAccessGranted();[span_9](end_span)
        if (allowed) {
            dndPref.setSummary(R.string.dnd_permission_allowed);
        } else {
            [span_10](start_span)dndPref.setSummary(R.string.dnd_permission_not_allowed);[span_10](end_span)
        }
        [span_11](start_span)return allowed;[span_11](end_span)
    }

    private void openDNDPermissionRequest() {
       Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
       [span_12](start_span)startActivity(intent);[span_12](end_span)
    }

    /**
     * 初始化 Wear OS 的 CapabilityClient 進行連線狀態查詢
     */
    private void initConnectivityCheck() {
        if (getContext() == null) return;

        // 查詢當前是否有支援 dnd_sync 的手錶在線上
        Wearable.getCapabilityClient(getContext())
                .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty()));

        // 定義狀態變更接聽器
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

    /**
     * 更新連線狀態 UI
     */
    private void updateConnectionUI(boolean isConnected) {
        if (connectivityPref != null) {
            if (isConnected) {
                connectivityPref.setSummary("已連線到智慧手錶");
            } else {
                connectivityPref.setSummary("未發現手錶，請確保藍牙已開啟且已配對");
            }
        }
    }
}
