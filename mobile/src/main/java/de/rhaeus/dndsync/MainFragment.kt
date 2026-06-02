package de.rhaeus.dndsync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.util.Log 
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

class MainFragment : Fragment() {

    private val isConnectedState = mutableStateOf(false)
    private val isDndAllowedState = mutableStateOf(false)
    
    // 用於觸發 Compose 刷新
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
                    val isDndAllowed by isDndAllowedState
                    val trigger by prefsTrigger

                    // 讀取本地手機的開關狀態
                    var dndSync by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("dndSync", true)) }
                    var powerSave by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("powerSave", false)) }
                    var wearPowerSave by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wearPowerSave", false)) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF7F9FC))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "DND Sync 智能控制中心",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1C1E),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        // 狀態卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                StatusRow(label = "手機勿擾權限", isActive = isDndAllowed, activeText = "已獲取", inactiveText = "未獲取 (點擊前往)") {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                StatusRow(label = "手錶連線狀態", isActive = isConnected, activeText = "已連線", inactiveText = "未連線 (檢查藍牙)") {}
                            }
                        }

                        Text(text = "同步設定 (手機端)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6C757D))

                        // 控制開關卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(4.dp)) {
                                SwitchRow(title = "啟用勿擾雙向同步", summary = "手機與手錶勿擾狀態實時保持一致", checked = dndSync) { checked ->
                                    sharedPreferences.edit().putBoolean("dndSync", checked).apply()
                                    prefsTrigger.value++
                                    syncSettingsToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))
                                SwitchRow(title = "手機端省電開關", summary = "當手機進入省電模式時觸發連動", checked = powerSave) { checked ->
                                    sharedPreferences.edit().putBoolean("powerSave", checked).apply()
                                    prefsTrigger.value++
                                    syncSettingsToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))
                                SwitchRow(title = "手錶端省電優化", summary = "開啟後，手錶端會採用 (先80%再40%) 的防吞雙連擊劇本", checked = wearPowerSave) { checked ->
                                    sharedPreferences.edit().putBoolean("wearPowerSave", checked).apply()
                                    prefsTrigger.value++
                                    syncSettingsToWear()
                                }
                            }
                        }

                        Text(text = "遠端管理手錶功能 (原手錶UI遷移)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6C757D))

                        // 原手錶UI遷移過來的操作卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                ActionRow(title = "引導手錶開啟【勿擾權限】", summary = "點擊令手錶端自動彈出勿擾授權介面") {
                                    sendRemoteCommandToWear("/open-wear-dnd-setting")
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))
                                ActionRow(title = "引導手錶開啟【無障礙服務】", summary = "點擊令手錶端自動彈出無障礙輔助功能介面") {
                                    sendRemoteCommandToWear("/open-wear-acc-setting")
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFE2E8F0))
                                ActionRow(title = "遠端測試手錶【寢室模式點擊】", summary = "手動觸發一次手錶控制中心下拉與模擬點擊劇本") {
                                    sendRemoteCommandToWear("/test-wear-click")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatusRow(label: String, isActive: Boolean, activeText: String, inactiveText: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontSize = 15.sp, color = Color(0xFF495057))
            Text(
                text = if (isActive) activeText else inactiveText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isActive) Color(0xFF28A745) else Color(0xFFDC3545)
            )
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

    @Composable
    fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp)
        ) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF007BFF))
            Text(text = summary, fontSize = 12.sp, color = Color(0xFF6C757D), modifier = Modifier.padding(top = 2.dp))
        }
    }

    override fun onResume() {
        super.onResume()
        checkDndPermission()
        registerConnectivityListener()
        syncSettingsToWear() // 每次進入主動向手錶重刷同步狀態
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
    }

    private fun checkDndPermission(): Boolean {
        val context = context ?: return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val allowed = manager.isNotificationPolicyAccessGranted
        if (isDndAllowedState.value != allowed) isDndAllowedState.value = allowed
        return allowed
    }

    /**
     * 🎯【核心大優化】：放棄極易因路徑匹配失敗丟包的 DataClient，
     * 改用直達、響應極快的 MessageClient 發送設置組態！
     */
    private fun syncSettingsToWear() {
        val context = context ?: return
        val pSave = if (sharedPreferences.getBoolean("powerSave", false)) 1 else 0
        val wSave = if (sharedPreferences.getBoolean("wearPowerSave", false)) 1 else 0
        val dSync = if (sharedPreferences.getBoolean("dndSync", true)) 1 else 0
        
        val payload = byteArrayOf(dSync.toByte(), pSave.toByte(), wSave.toByte())

        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(node.id, "/settings-sync-msg", payload)
                    .addOnSuccessListener { Log.d("MobileSync", "成功發送配置快遞到手錶") }
            }
        }
    }

    /**
     * 發送遠端控制命令給手錶
     */
    private fun sendRemoteCommandToWear(path: String) {
        val context = context ?: return
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                Toast.makeText(context, "未找到已連線的手錶，無法傳送指令", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(node.id, path, byteArrayOf(1))
                    .addOnSuccessListener {
                        Toast.makeText(context, "已遠端觸發手錶動作", Toast.LENGTH_SHORT).show()
                    }
            }
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
