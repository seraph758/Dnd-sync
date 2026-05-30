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

    // 1. 全域變數降級為普通 Boolean 與安全封裝（避免 IR 捕獲 Bug）
    private var isConnected = false
    private var isDndAllowed = false
    
    // 透過一個內部的 Compose State 用於向 UI 推送變更
    private val uiTriggerState = mutableStateOf(0)
    
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val context = requireContext().applicationContext
                val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

                // 2. 透過簡單的靜態數值訂閱，完全避開對 Fragment 執行個體 (this) 的閉包捕獲
                // 每次 uiTriggerState.value 改變時，Compose 會重新讀取最新值
                val trigger = uiTriggerState.value 
                
                val sharedPrefs = remember(context) {
                    context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                }
                
                val initDndSync = remember(trigger) { sharedPrefs.getBoolean("dnd_sync_key", true) }
                val initDndAsBedtime = remember(trigger) { sharedPrefs.getBoolean("dnd_as_bedtime_key", false) }
                val initBedtimeSync = remember(trigger) { sharedPrefs.getBoolean("bedtime_sync_key", false) }
                val initPowerSave = remember(trigger) { sharedPrefs.getBoolean("power_save_key", false) }

                MaterialTheme(colorScheme = colorScheme) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                if (manager?.isNotificationPolicyAccessGranted == false) {
                                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
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
        if (isDndAllowed != allowed) {
            isDndAllowed = allowed
            uiTriggerState.value += 1 // 觸發 Compose 刷新
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

// ==========================================
// 3. 關鍵修正：將 Composable 移出 Fragment 類別之外，
// 變成 Top-level 獨立函式（靜態函式），徹底根除所有隱式 'this$0' 捕獲引起的編譯錯誤
// ==========================================
@Composable
fun SettingsScreen(
    initialDndSync: Boolean,
    initialDndAsBedtime: Boolean,
    initialBedtimeSync: Boolean,
    initialPowerSave: Boolean,
    isConnected: Boolean,
    isDndAllowed: Boolean,
    onPrefChanged: (String, Boolean) -> Unit,
    onPermissionClick: () -> Unit
) {
    var dndSync by remember(initialDndSync) { mutableStateOf(initialDndSync) }
    var dndAsBedtime by remember(initialDndAsBedtime) { mutableStateOf(initialDndAsBedtime) }
    var bedtimeSync by remember(initialBedtimeSync) { mutableStateOf(initialBedtimeSync) }
    var powerSave by remember(initialPowerSave) { mutableStateOf(initialPowerSave) }

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
                summary = if (isConnected) "已成功連線到手錶" else "未發現配對手錶，請檢查藍牙或 Wear OS App"
            )
        }

        CategoryGroup(title = "同步設定") {
            SwitchItem(title = "同步勿擾模式", summary = "當手機開啟勿擾時，自動同步至手錶", checked = dndSync) { nextValue ->
                dndSync = nextValue
                onPrefChanged("dnd_sync_key", nextValue)
            }

            SwitchItem(title = "將勿擾視為就寢模式", summary = "開啟後，手機進入勿擾時手錶將同步觸發就寢模式", checked = dndAsBedtime) { nextValue ->
                dndAsBedtime = nextValue
                onPrefChanged("dnd_as_bedtime_key", nextValue)
            }

            SwitchItem(title = "同步就寢模式", summary = "獨立同步手機與手錶的就寢狀態", checked = bedtimeSync) { nextValue ->
                bedtimeSync = nextValue
                onPrefChanged("bedtime_sync_key", nextValue)
            }

            SwitchItem(
                title = "聯動省電模式", 
                summary = "當上述就寢或勿擾觸發時，自動開啟省電", 
                checked = if (isPowerSaveEnabled) powerSave else false, 
                enabled = isPowerSaveEnabled
            ) { nextValue ->
                powerSave = nextValue
                onPrefChanged("power_save_key", nextValue)
            }
        }

        CategoryGroup(title = "權限管理") {
            CardItem(
                title = "勿擾模式訪問權限", 
                summary = if (isDndAllowed) "DND access granted" else "DND access denied",
                onClick = onPermissionClick
            )
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
