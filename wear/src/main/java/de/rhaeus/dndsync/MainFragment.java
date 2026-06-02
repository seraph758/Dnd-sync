package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class MainFragment extends PreferenceFragmentCompat {
    private Preference dndPref;
    private Preference accPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        dndPref = findPreference("dnd_permission_key");
        accPref = findPreference("acc_permission_key");

        if (dndPref != null) {
            dndPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                return true;
            });
        }

        if (accPref != null) {
            accPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissions();
    }

    private void updatePermissions() {
        Context ctx = getContext();
        if (ctx == null) return;

        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean dndAllowed = manager != null && manager.isNotificationPolicyAccessGranted();
        if (dndPref != null) {
            dndPref.setSummary(dndAllowed ? "勿擾存取權限：已開啟" : "勿擾存取權限：未開啟，請點擊授權");
        }

        boolean accAllowed = DNDSyncAccessService.getSharedInstance() != null;
        if (accPref != null) {
            accPref.setSummary(accAllowed ? "無障礙自動點擊：已就緒" : "無障礙自動點擊：未開啟，請點擊授權");
        }
    }
}
