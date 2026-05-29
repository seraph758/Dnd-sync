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

/**
 * 手機端主設定介面 Fragment
 * 整合了勿擾權限檢測、手錶藍牙連線狀態動態監聽，並修復了 Material 3 頂部標題重疊的問題
 */
public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener capabilityChangedListener;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // 從 XML 資源檔案加載設定佈局
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // 核心修復：徹底關閉 PreferenceFragment 內建的舊版隱藏標題，防止它與我們自定義的 Toolbar 重疊
        if (getPreferenceScreen() != null) {
            getPreferenceScreen().setTitle(null);
        }

        // 綁定 XML 裡的控制項
        dndPref = findPreference("dnd_permission_key");
        connectivityPref = findPreference("connectivity_state_key");

        // 配置勿擾權限點擊事件
        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (!checkDNDPermission()) {
                        openDNDPermissionRequest(); // 沒有權限則引導跳轉系統設定
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
        checkDNDPermission(); // 每次回到介面重新整理權限狀態
        registerConnectivityListener(); // 註冊藍牙狀態監聽器
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityListener(); // 離開介面時註銷監聽器，防止記憶體洩漏
    }

    /**
     * 檢查並重新整理勿擾模式權限狀態
     */
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

    /**
     * 跳轉到系統的「通知存取權限/勿擾權限」設定頁面
     */
    private void openDNDPermissionRequest() {
        Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        startActivity(intent);
    }

    /**
     * 初始化連線狀態，主動尋找手錶端暴露的 "dnd_sync" 身份暗號
     */
    private void initConnectivityCheck() {
        if (getContext() == null) return;

        // 異步查詢當前是否有處於啟用狀態的手錶節點
        Wearable.getCapabilityClient(getContext())
                .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> updateConnectionUI(!capabilityInfo.getNodes().isEmpty()));

        // 定義動態監聽器：當手錶斷開藍牙或重新連上時即時觸發
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
     * 根據動態回傳的藍牙連線結果，即時刷新介面文字
     */
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
