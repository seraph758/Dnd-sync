package de.rhaeus.dndsync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class MainFragment : Fragment() {

    private val isConnectedState = mutableStateOf(false)
    private val isNotificationAllowedState = mutableStateOf(false)
    private val prefsTrigger = mutableStateOf(0)

    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        sharedPreferences = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

        return ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    val isConnected by isConnectedState
                    val isNotificationAllowed by isNotificationAllowedState
                    val trigger by prefsTrigger

                    // 從手機本地儲存讀取這 4 個原本屬於手錶功能的開關狀態
                    var dndSyncMode by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("dnd_sync_switch", true)) }
                    var phonePowerSaveByLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("phone_power_save_link", false)) }
                    var wearPowerSaveResponse by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_power_save_response", false)) }
                    var wearVibrateOnSync by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_vibrate_on_sync", true)) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF7F9FC))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "DND Sync 同步控制中心",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1C1E)
                        )

                        // 1. 手機端狀態看板卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        // 🎯 修正：正確跳轉到手機系統的「通知存取權限/通知監聽器」設定頁面
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "手機通知存取權限", fontSize = 15.sp, color = Color(0xFF495057))
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
                                    Text(text = "手錶連線狀態", fontSize = 15.sp, color = Color(0xFF495057))
                                    Text(
                                        text = if (isConnected) "已連線" else "未連線 (檢查藍牙/配對)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        // 2. 收納手錶設定的功能控制面板
                        Text(text = "手錶端功能遠端控制", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6C757D))

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                // 開關 A: 啟用勿擾狀態同步
                                SwitchRow(
                                    title = "啟用勿擾狀態同步",
                                    summary = "開啟後，手機的勿擾狀態將自動同步發送到手錶",
                                    checked = dndSyncMode
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("dnd_sync_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))
                                
                                // 開關 B: 手機端省電連動
                                SwitchRow(
                                    title = "手機端省電連動",
                                    summary = "當手機進入省電模式時，觸發狀態變更",
                                    checked = phonePowerSaveByLink
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("phone_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))

                                // 開關 C: 手錶端省電模式響應（防吞連擊劇本開關）
                                SwitchRow(
                                    title = "手錶端省電模式響應",
                                    summary = "開啟後，若觸發省電，手錶將執行 (先點擊80%再點擊40%) 的防吞劇本",
                                    checked = wearPowerSaveResponse
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_response", checked).apply()
                                    prefsTrigger.value++
                                    pushSettingsToWear()
                                }

                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))

                                // 開關 D: 同步時震動開關
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
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF212529))
                Text(text = summary, fontSize = 12.sp, color = Color(0xFF6C757D))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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

    // 🎯 修正：精確檢查手機本機的「通知存取/通知監聽權限」
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

    /**
     * 🎯 利用最暢通無阻的 DataClient "/dnd_state" 管道將開關配置送達手錶快取
     */
    private fun pushSettingsToWear() {
        val context = context ?: return
        val dndSync = sharedPreferences.getBoolean("dnd_sync_switch", true)
        val pSave = sharedPreferences.getBoolean("phone_power_save_link", false)
        val wSave = sharedPreferences.getBoolean("wear_power_save_response", false)
        val wVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true)

        val dataMapRequest = PutDataMapRequest.create("/dnd_state").apply {
            dataMap.putBoolean("dnd_sync_switch", dndSync)
            dataMap.putBoolean("phone_power_save_link", pSave)
            dataMap.putBoolean("wear_power_save_response", wSave) // 開關 C 直接下發
            dataMap.putBoolean("wear_vibrate_on_sync", wVibrate)   // 開關 D 直接下發
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }

        Wearable.getDataClient(context).putDataItem(dataMapRequest.asPutDataRequest())
            .addOnSuccessListener {
                Log.d("MobileSettings", "【通訊成功】已將最新4項控制配置同步至手錶 DataClient")
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
