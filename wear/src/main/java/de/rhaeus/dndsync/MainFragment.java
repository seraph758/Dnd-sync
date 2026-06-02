package de.rhaeus.dndsync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
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

        // 1. 綁定需要顯示的組件
        connectivityPref = findPreference("connectivity_state_key");
        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");

        // =======================================================
        // 🎯 核心修正：強行從 UI 樹節點中完美抹去被手機託管的開關與分組
        // =======================================================
        try {
            Preference dndSyncSwitch = findPreference("dnd_sync_key");
            Preference bedtimeSwitch = findPreference("bedtime_key");
            Preference vibrateSwitch = findPreference("vibrate_key");

            // 正確移除包裹在 Category 內部的子組件
            if (dndSyncSwitch != null && dndSyncSwitch.getParent() != null) {
                dndSyncSwitch.getParent().removePreference(dndSyncSwitch);
            }
            if (bedtimeSwitch != null && bedtimeSwitch.getParent() != null) {
                bedtimeSwitch.getParent().removePreference(bedtimeSwitch);
            }
            if (vibrateSwitch != null && vibrateSwitch.getParent() != null) {
                vibrateSwitch.getParent().removePreference(vibrateSwitch);
            }

            // 尋找並強行隱藏那個空的 “遠端同步控制（已由手機託管）” 分組標題
            for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
                Preference pref = getPreferenceScreen().getPreference(i);
                if (pref instanceof PreferenceCategory) {
                    CharSequence title = pref.getTitle();
                    if (title != null && title.toString().contains("遠端同步控制")) {
                        pref.setVisible(false); // 徹底讓這個分組標題在畫面上隱形
                    }
                }
            }
        } catch (Exception e) {
            Log.e("WearMainFragment", "清除託管組件時發生錯誤: " + e.getMessage());
        }
        // =======================================================

        // 2. 通知權限只做檢測，不賦予點擊跳轉事件
        if (dndPref != null) {
            dndPref.setSelectable(false); 
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

        // 🎯 修正：將 ctx.contentResolver 改為 ctx.getContentResolver() 符合 Java 語法
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
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
