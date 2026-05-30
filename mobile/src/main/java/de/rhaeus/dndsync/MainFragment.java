package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Wearable;

/**
 * 手機端主設定介面 Fragment
 * 整合了勿擾權限檢測、手錶藍牙連線狀態動態監聽
 * 核心修復：徹底解決標題重疊，並強制將舊版小圓點開關升級為 Material 3 大膠囊樣式
 */
public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // 從 XML 資源檔案加載設定佈局
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 1. 徹底關閉 PreferenceFragment 內建的舊版隱藏標題，防止它與我們自定義的 Toolbar 重疊
        if (getPreferenceScreen() != null) {
            getPreferenceScreen().setTitle(null);
        }

        // 2. 綁定 XML 裡的控制項
        dndPref = findPreference("dnd_permission_key");
        connectivityPref = findPreference("connectivity_state_key");

        // 3. 🎯 強制開啟 Material 3 UI 核心魔改：
        // 尋找佈局中「同步勿擾模式」的開關控制項（請確保 "dnd_sync_key" 與你 root_preferences.xml 裡的 key 一致）
        SwitchPreferenceCompat dndStatusSwitch = findPreference("dnd_sync_key");
        if (dndStatusSwitch != null) {
            // 透過 Java 程式碼，強行將它的渲染佈局替換為 Material 3 規範的正統大膠囊開關佈局
            dndStatusSwitch.setWidgetLayoutResource(com.google.android.material.R.layout.m3_preference_widget_switch);
        }

        // 配置勿擾權限點擊事件
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (!checkDNDPermission()) {
                        openDNDPermissionRequest();
                    } else {
                        Toast.makeText(getContext(), "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
        }
        
        // 初始檢查權限與藍牙連線狀態
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
