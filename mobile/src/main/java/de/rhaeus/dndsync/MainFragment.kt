package de.rhaeus.dndsync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

class MainFragment : Fragment() {

    private var isConnected = false
    private var isDndAllowed = false
    private val uiTriggerState = androidx.compose.runtime.mutableStateOf(0)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // 關鍵修正：明確定義 composeView 變數，確保最終 return 結構絕對是 View，排除 Unit 衝突
        val composeView = ComposeView(requireContext())
        
        composeView.setContent {
            val appContext = remember { requireContext().applicationContext }
            val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)

            val trigger = uiTriggerState.value
            val sharedPrefs = remember(trigger) {
                appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)
            }

            val initDndSync = remember(trigger) { sharedPrefs.getBoolean("dnd_sync_key", true) }
            val initDndAsBedtime = remember(trigger) { sharedPrefs.getBoolean("dnd_as_bedtime_key", false) }
            val initBedtimeSync = remember(trigger) { sharedPrefs.getBoolean("bedtime_sync_key", false) }
            val initPowerSave = remember(trigger) { sharedPrefs.getBoolean("power_save_key", false) }

            MaterialTheme(colorScheme = colorScheme) {
                // 關鍵修正：使用全包名指定，防止編譯器誤識別為 android.view.Surface
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(), 
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 呼叫位於 SettingsScreen.kt 的頂層獨立函數
                    SettingsScreen(
                        initialDndSync = initDndSync,
                        initialDndAsBedtime = initDndAsBedtime,
                        initialBedtimeSync = initBedtimeSync,
                        initialPowerSave = initPowerSave,
                        isConnected = isConnected,
                        isDndAllowed = isDndAllowed,
                        onPrefChanged = { key, value ->
                            sharedPrefs.edit().putBoolean(key, value).apply()
                        },
                        onPermissionClick = {
                            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            if (manager?.isNotificationPolicyAccessGranted == false) {
                                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                appContext.startActivity(intent)
                            } else {
                                Toast.makeText(appContext, "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
        
        return composeView
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
        if (isDndAllowed != allowed) {
            isDndAllowed = allowed
            uiTriggerState.value += 1
        }
        return allowed
    }

    private fun initConnectivityCheck() {
        val context = context ?: return
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { capabilityInfo ->
            val connected = !capabilityInfo.nodes.isEmpty()
            if (isConnected != connected) {
                isConnected = connected
                uiTriggerState.value += 1
            }
        }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            val connected = !capabilityInfo.nodes.isEmpty()
            if (isConnected != connected) {
                isConnected = connected
                uiTriggerState.value += 1
            }
        }
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        initConnectivityCheck()
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        val context = context ?: return
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).removeListener(it) }
    }
}
