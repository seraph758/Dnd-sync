package de.rhaeus.dndsync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

class MainFragment : Fragment() {

    // ==========================================
    // 1. 修正狀態宣告：改用普通變數在 onCreate 初始化，將狀態保留在 UI 層或使用正規監聽
    // ==========================================
    private var initialDndSync by mutableStateOf(true)
    private var initialDndAsBedtime by mutableStateOf(false)
    private var initialBedtimeSync by mutableStateOf(false)
    private var initialPowerSave by mutableStateOf(false)

    // 連線與權限屬於全域監聽狀態，改用類別成員委託
    private var isConnectedState by mutableStateOf(false)
    private var isDndAllowedState by mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    private val sharedPrefs by lazy {
        requireContext().getSharedPreferences("${requireContext().packageName}_preferences", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialDndSync = sharedPrefs.getBoolean("dnd_sync_key", true)
        initialDndAsBedtime = sharedPrefs.getBoolean("dnd_as_bedtime_key", false)
        initialBedtimeSync = sharedPrefs.getBoolean("bedtime_sync_key", false)
        initialPowerSave = sharedPrefs.getBoolean("power_save_key", false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val context = requireContext()
                val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

                MaterialTheme(colorScheme = colorScheme) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsScreen() {
        // ==========================================
        // 2. 正規 Compose 狀態維護：在 Composable 內部記得（remember）狀態
        // 這樣做能完美隔絕與 Inline Column 閉包的變數捕獲衝突
        // ==========================================
        var dndSync by remember { mutableStateOf(initialDndSync) }
        var dndAsBedtime by remember { mutableStateOf(initialDndAsBedtime) }
        var bedtimeSync by remember { mutableStateOf(initialBedtimeSync) }
        var powerSave by remember { mutableStateOf(initialPowerSave) }

        // 使用 derivedStateOf 衍生狀態，聯動省電模式是否可用
        val isPowerSaveEnabled by remember {
            derivedStateOf { dndAsBedtime || bedtimeSync }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CategoryGroup(title = "連線狀態") {
                CardItem(
                    title = "雙端連通狀態", 
                    summary = if (isConnectedState) "已成功連線到手錶" else "未發現配對手錶，請檢查藍牙或 Wear OS App"
                )
            }

            CategoryGroup(title = "同步設定") {
                SwitchItem(title = "同步勿擾模式", summary = "當手機開啟勿擾時，自動同步至手錶", checked = dndSync) { nextValue ->
                    dndSync = nextValue
                    sharedPrefs.edit().putBoolean("dnd_sync_key", nextValue).apply()
                }

                SwitchItem(title = "將勿擾視為就寢模式", summary = "開啟後，手機進入勿擾時手錶將同步觸發就寢模式", checked = dndAsBedtime) { nextValue ->
                    dndAsBedtime = nextValue
                    sharedPrefs.edit().putBoolean("dnd_as_bedtime_key", nextValue).apply()
                }

                SwitchItem(title = "同步就寢模式", summary = "獨立同步手機與手錶的就寢狀態", checked = bedtimeSync) { nextValue ->
                    bedtimeSync = nextValue
                    sharedPrefs.edit().putBoolean("bedtime_sync_key", nextValue).apply()
                }

                SwitchItem(
                    title = "聯動省電模式", 
                    summary = "當上述就寢或勿擾觸發時，自動開啟省電", 
                    checked = if (isPowerSaveEnabled) powerSave else false, 
                    enabled = isPowerSaveEnabled
                ) { nextValue ->
                    powerSave = nextValue
                    sharedPrefs.edit().putBoolean("power_save_key", nextValue).apply()
                }
            }

            CategoryGroup(title = "權限管理") {
                CardItem(title = "勿擾模式訪問權限", summary = if (isDndAllowedState) "DND access granted" else "DND access denied") {
                    if (!checkDNDPermission()) openDNDPermissionRequest() else Toast.makeText(requireContext(), "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Composable
    fun CategoryGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth(), content = content)
        }
    }

    @Composable
    fun SwitchItem(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = summary, fontSize = 13.sp, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    @Composable
    fun CardItem(title: String, summary: String, onClick: (() -> Unit)? = null) {
        val modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
        Row(modifier = modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        isDndAllowedState = allowed
        return allowed
    }

    private fun openDNDPermissionRequest() {
        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun initConnectivityCheck() {
        val context = context ?: return
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { capabilityInfo ->
            isConnectedState = !capabilityInfo.nodes.isEmpty()
        }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            isConnectedState = !capabilityInfo.nodes.isEmpty()
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
