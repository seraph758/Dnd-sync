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

public class MainFragment 
        extends PreferenceFragmentCompat {
        
    private Preference dndPref;
    private Preference connectivityPref;
    private CapabilityClient.OnCapabilityChangedListener 
            capabilityChangedListener;

    @Override
    public void onCreatePreferences(
            Bundle savedInstanceState, 
            String rootKey) {
            
        setPreferencesFromResource(
            R.xml.root_preferences, 
            rootKey
        );

        if (getPreferenceScreen() != null) {
            getPreferenceScreen()
                .setTitle(null);
        }

        dndPref = findPreference(
            "dnd_permission_key"
        );
        connectivityPref = findPreference(
            "connectivity_state_key"
        );

        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(
                            Preference preference) {
                        if (!checkDNDPermission()) {
                            openDNDPermissionRequest();
                        } else {
                            Toast.makeText(
                                getContext(), 
                                "DND Permission allowed", 
                                Toast.LENGTH_SHORT
                            ).show();
                        }
                        return true;
                    }
                }
            );
        }
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
        NotificationManager mNotificationManager = 
            (NotificationManager) getContext()
                .getSystemService(
                    Context.NOTIFICATION_SERVICE
                );
        if (mNotificationManager == null) return false;

        boolean allowed = mNotificationManager
            .isNotificationPolicyAccessGranted();
            
        if (dndPref != null) {
            if (allowed) {
                dndPref.setSummary(
                    R.string.dnd_permission_allowed
                );
            } else {
                dndPref.setSummary(
                    R.string.dnd_permission_not_allowed
                );
            }
        }
        return allowed;
    }

    private void openDNDPermissionRequest() {
        Intent intent = new Intent(
            android.provider.Settings
                .ACTION_NOTIFICATION_LISTENER_SETTINGS
        );
        startActivity(intent);
    }

    private void initConnectivityCheck() {
        if (getContext() == null) return;

        Wearable.getCapabilityClient(getContext())
            .getCapability(
                "dnd_sync", 
                CapabilityClient.FILTER_REACHABLE
            )
            .addOnSuccessListener(capabilityInfo -> 
                updateConnectionUI(
                    !capabilityInfo.getNodes().isEmpty()
                )
            );

        capabilityChangedListener = capabilityInfo -> 
            updateConnectionUI(
                !capabilityInfo.getNodes().isEmpty()
            );
    }

    private void registerConnectivityListener() {
        if (getContext() != null && 
                capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext())
                .addListener(
                    capabilityChangedListener, 
                    "dnd_sync"
                );
        }
    }

    private void unregisterConnectivityListener() {
        if (getContext() != null && 
                capabilityChangedListener != null) {
            Wearable.getCapabilityClient(getContext())
                .removeListener(
                    capabilityChangedListener
                );
        }
    }

    private void updateConnectionUI(
            boolean isConnected) {
            
        if (connectivityPref != null) {
            if (isConnected) {
                connectivityPref.setSummary(
                    "已成功連線到手錶"
                );
            } else {
                connectivityPref.setSummary(
                    "未發現配對手錶，請檢查藍牙或 Wear OS App"
                );
            }
        }
    }
}
