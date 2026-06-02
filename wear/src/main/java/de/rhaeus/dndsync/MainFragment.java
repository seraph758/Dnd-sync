package de.rhaeus.dndsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference connectivityPref;
    private Preference dndPref;
    private Preference accPref;

    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 1. 綁定組件（對應您的 root_preferences.xml 內定義的 Key）
        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");

        // 🎯 核心遷移：徹底拔掉和移除手錶端舊的 bedtime_key 等開關 Preference 組件
        Preference oldBedtimePref = findPreference("bedtime_key");
        if (oldBedtimePref != null) {
            getPreferenceScreen().removePreference(oldBedtimePref);
        }

        // 2. 🎯 通知權限只做檢測，不賦予 setOnPreferenceClickListener 點擊跳轉事件
        if (dndPref != null) {
            dndPref.setSelectable(false); // 設置為不可選取、不可點擊點按
        }

        // 3. 無障礙權限保持可點擊引導跳轉手錶本機系統
        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(getContext(), "無法跳轉，請在手錶系統設定中手動開啟無障礙", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        initConnectivityCheck();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatus();
        registerConnectivityListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener();
    }

    private void updatePermissionStatus() {
        Context ctx = getContext();
        if (ctx == null) return;

        // 🎯 精確檢測：手錶 DNDNotificationService 通知監聽權限是否被激活
        String flat = Settings.Secure.getString(ctx.contentResolver(), "enabled_notification_listeners");
        boolean notificationAllowed = flat != null && flat.contains(ctx.getPackageName());
        if (dndPref != null) {
            dndPref.setSummary(notificationAllowed ? "通知接聽權限：已啟用" : "通知接聽權限：未啟用 (請透過ADB授權)");
        }

        // 檢測無障礙點擊服務是否開啟
        boolean accAllowed = DNDSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "無障礙自動點擊：已就緒" : "無障礙自動點擊：未開啟，點擊去授權");
        }
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
            connectivityPref.setSummary(isConnected ? "已成功連線到手機" : "未發現配對手機，請檢查藍牙");
        }
    }
}
