package de.rhaeus.dndsync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class MainFragment : Fragment() {

    private val isConnectedState = mutableStateOf(false)
    private val isNotificationAllowedState = mutableStateOf(false)
    private val prefsTrigger = mutableStateOf(0)

    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    
    // 🎯 修正：將 val 改為 var，這樣 lateinit 才可以正常編譯，且允許後面重新賦值
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        sharedPreferences = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

        return ComposeView(context).apply {
            setContent {
                val darkTheme = isSystemInDarkTheme()
                val currentContext = LocalContext.current
                
                // 🎯 真正的動態取色：Android 12+ 從系統桌面桌布提取色彩，舊版本回退到預設調色盤
                val colorScheme = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        if (darkTheme) dynamicDarkColorScheme(currentContext) else dynamicLightColorScheme(currentContext)
                    }
                    darkTheme -> darkColorScheme(
                        background = Color(0xFF121212),
                        surface = Color(0xFF1E1E1E),
                        onBackground = Color(0xFFE3E2E6),
                        onSurface = Color(0xFFE3E2E6)
                    )
                    else -> lightColorScheme(
                        background = Color(0xFFF7F9FC),
                        surface = Color.White,
                        onBackground = Color(0xFF1A1C1E),
                        onSurface = Color(0xFF495057)
                    )
                }

                // 🎯 狀態欄圖標黑白自我適應：亮色模式下狀態欄用黑字，暗色模式下用白字
                val window = activity?.window
                if (window != null) {
                    val decorView = window.decorView
                    WindowInsetsControllerCompat(window, decorView).isAppearanceLightStatusBars = !darkTheme
                }

                MaterialTheme(colorScheme = colorScheme) {
                    val isConnected by isConnectedState
                    val isNotificationAllowed by isNotificationAllowedState
                    val trigger by prefsTrigger

                    var dndSyncMode by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("dnd_sync_switch", true)) }
                    var phonePowerSaveByLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("phone_power_save_link", false)) }
                    var wearPowerSaveResponse by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_power_save_response", false)) }
                    var wearVibrateOnSync by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_vibrate_on_sync", true)) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background) // 全螢幕背景跟隨桌布色，消除色彩斷層
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "DND Sync 同步控制中心",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // 1. 狀態看板卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "手機通知存取權限", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = if (isNotificationAllowed) "已授權" else "未授權 (點擊前往)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isNotificationAllowed) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "手錶連線狀態", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = if (isConnected) "已連線" else "未連線 (檢查藍牙/配對)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        // 2. 控制面板標題
                        Text(
                            text = "手錶端功能遠端控制", 
                            fontSize = 14.sp, 
                            fontWeight = FontWeight.SemiBold, 
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                SwitchRow(
                                    title = "啟用勿擾狀態同步",
                                    summary = "開啟後，手機的勿擾狀態將自動同步發送到手錶",
                                    checked = dndSyncMode
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("dnd_sync_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }
                                
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp), 
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )
                                
                                SwitchRow(
                                    title = "手機端省電連動",
                                    summary = "當手機進入省電模式時，觸發狀態變更",
                                    checked = phonePowerSaveByLink
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("phone_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }
                                
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp), 
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )

                                SwitchRow(
                                    title = "手錶端省電模式響應",
                                    summary = "開啟後，若觸發省電，手錶將執行 (先點擊80%再點擊40%) 的防吞劇本",
                                    checked = wearPowerSaveResponse
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_response", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp), 
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )

                                SwitchRow(
                                    title = "同步成功時震動反饋",
                                    summary = "當手錶成功接收勿擾變更並執行手勢點擊時，手錶本機觸發短震動",
                                    checked = wearVibrateOnSync
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_vibrate_on_sync", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                Text(text = summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Switch(
                checked = checked, 
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        registerConnectivityListener()
        pushSettingsToWear()
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
    }

    private fun checkNotificationPermission(): Boolean {
        val context = context ?: return false
        val packageName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val allowed = flat != null && flat.contains(packageName)
        if (isNotificationAllowedState.value != allowed) {
            isNotificationAllowedState.value = allowed
        }
        return allowed
    }

    private fun pushSettingsToWear() {
        val context = context ?: return
        val dndSync = sharedPreferences.getBoolean("dnd_sync_switch", true)
        val pSave = sharedPreferences.getBoolean("phone_power_save_link", false)
        val wSave = sharedPreferences.getBoolean("wear_power_save_response", false)
        val wVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true)

        val dataMapRequest = PutDataMapRequest.create("/dnd_state").apply {
            dataMap.putBoolean("dnd_sync_switch", dndSync)
            dataMap.putBoolean("phone_power_save_link", pSave)
            dataMap.putBoolean("wear_power_save_response", wSave)
            dataMap.putBoolean("wear_vibrate_on_sync", wVibrate)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }

        Wearable.getDataClient(context).putDataItem(dataMapRequest.asPutDataRequest())
            .addOnSuccessListener {
                Log.d("MobileSettings", "【通訊成功】已將 4 項設定同步至手錶")
            }
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        Wearable.getCapabilityClient(context)
            .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilityInfo ->
                isConnectedState.value = capabilityInfo.nodes.isNotEmpty()
            }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            isConnectedState.value = capabilityInfo.nodes.isNotEmpty()
        }
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
}
