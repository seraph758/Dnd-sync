package de.rhaeus.dndsync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

class MainFragment : Fragment() {

    // 1. 將通訊與權限狀態提升為類別成員，由 Compose 自動追蹤更新
    private val isConnectedState = mutableStateOf(false)
    private val isDndAllowedState = mutableStateOf(false)
    
    // 2. 用於在 SharedPreferences 變更時強制觸發 UI 刷新
    private val prefsTrigger = mutableStateOf(0)
    
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在生命週期早期初始化，避免在 onCreateView 內重複創建或擷取局部變數
        val appContext = requireContext().applicationContext
        sharedPrefs = appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val appContext = requireContext().applicationContext
                val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)

                // 透過讀取 prefsTrigger.value，讓這個 Composable 區塊訂閱偏好設定的刷新
                val trigger = prefsTrigger.value
                
                // 直接從類別成員 sharedPrefs 中讀取，不再使用局部 remember 擷取
                val initDndSync = sharedPrefs.getBoolean("dnd_sync_key", true)
                val initDndAsBedtime = sharedPrefs.getBoolean("dnd_as_bedtime_key", false)
                val initBedtimeSync = sharedPrefs.getBoolean("bedtime_sync_key", false)
                val initPowerSave = sharedPrefs.getBoolean("power_save_key", false)

                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SettingsScreen(
                            initialDndSync = initDndSync,
                            initialDndAsBedtime = initDndAsBedtime,
                            initialBedtimeSync = initBedtimeSync,
                            initialPowerSave = initPowerSave,
                            isConnected = isConnectedState.value,
                            isDndAllowed = isDndAllowedState.value,
                            onPrefChanged = { key, value ->
                                // 透過成員變數操作，徹底切斷 Lambda 深度嵌套擷取
                                sharedPrefs.edit().putBoolean(key, value).apply()
                                prefsTrigger.value += 1 // 變更時累加，強制觸發界面重繪
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
        }
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
        if (isDndAllowedState.value != allowed) {
            isDndAllowedState.value = allowed
        }
        return allowed
    }

    private fun initConnectivityCheck() {
        val context = context ?: return
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { capabilityInfo ->
            val connected = !capabilityInfo.nodes.isEmpty()
            if (isConnectedState.value != connected) {
                isConnectedState.value = connected
            }
        }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            val connected = !capabilityInfo.nodes.isEmpty()
            if (isConnectedState.value != connected) {
                isConnectedState.value = connected
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
