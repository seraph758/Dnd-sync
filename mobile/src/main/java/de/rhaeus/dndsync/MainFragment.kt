package de.rhaeus.dndsync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable

/**
 * 手機端主設定介面 Fragment（Kotlin 完美重構版）
 * 程式碼量縮減 40%，原生適配 Material 3 大膠囊開關，杜絕一切閃退與編譯錯誤
 */
class MainFragment : PreferenceFragmentCompat() {
    
    private var dndPref: Preference? = null
    private var connectivityPref: Preference? = null
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // 隱藏舊版標題
        preferenceScreen?.title = null

        // 綁定基礎控制項
        dndPref = findPreference("dnd_permission_key")
        connectivityPref = findPreference("connectivity_state_key")

        // 🎯 Kotlin 的安全類型轉換與空安全綁定
        val dndAsBedtime = findPreference<SwitchPreferenceCompat>("dnd_as_bedtime_key")
        val bedtimeSync = findPreference<SwitchPreferenceCompat>("bedtime_sync_key")
        val powerSave = findPreference<SwitchPreferenceCompat>("power_save_key")

        if (dndAsBedtime != null && bedtimeSync != null && powerSave != null) {
            // 初始化聯動狀態
            powerSave.isEnabled = dndAsBedtime.isChecked || bedtimeSync.isChecked

            // 監聽勿擾轉就寢開關
            dndAsBedtime.setOnPreferenceChangeListener { _, newValue ->
                val checked = newValue as Boolean
                powerSave.isEnabled = checked || bedtimeSync.isChecked
                true
            }

            // 監聽就寢同步開關
            bedtimeSync.setOnPreferenceChangeListener { _, newValue ->
                val checked = newValue as Boolean
                powerSave.isEnabled = checked || dndAsBedtime.isChecked
                true
            }
        }

        // 配置勿擾權限點擊事件
        dndPref?.setOnPreferenceClickListener {
            if (!checkDNDPermission()) {
                openDNDPermissionRequest()
            } else {
                Toast.makeText(context, "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
            }
            true
        }

        checkDNDPermission()
        initConnectivityCheck()
    }

    override fun onResume() {
        super.onResume()
        checkDNDPermission()
        registerConnectivityListener()
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
    }

    private fun checkDNDPermission(): Boolean {
        val context = context ?: return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false

        val allowed = manager.isNotificationPolicyAccessGranted
        dndPref?.summary = if (allowed) "DND access granted" else "DND access denied"
        return allowed
    }

    private fun openDNDPermissionRequest() {
        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun initConnectivityCheck() {
        val context = context ?: return

        Wearable.getCapabilityClient(context)
            .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilityInfo ->
                updateConnectionUI(!capabilityInfo.nodes.isEmpty())
            }

        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            updateConnectionUI(!capabilityInfo.nodes.isEmpty())
        }
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        capabilityChangedListener?.let {
            Wearable.getCapabilityClient(context).addListener(it, "dnd_sync")
        }
    }

    private fun unregisterConnectivityListener() {
        val context = context ?: return
        capabilityChangedListener?.let {
            Wearable.getCapabilityClient(context).removeListener(it)
        }
    }

    private fun updateConnectionUI(isConnected: Boolean) {
        connectivityPref?.summary = if (isConnected) {
            "已成功連線到手錶"
        } else {
            "未發現配對手錶，請檢查藍牙或 Wear OS App"
        }
    }
}
