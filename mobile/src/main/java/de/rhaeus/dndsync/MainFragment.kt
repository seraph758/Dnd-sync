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
// 🎯 核心補丁：導入委託屬性所需的擴充功能，確保 by 語法正常運作
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * 手機端主設定介面 Fragment（Jetpack Compose 視覺完全體）
 * 實現大圓角膠囊卡片外觀，支援 Monet 系統動態變色，邏輯完美對齊。
 */
class MainFragment : Fragment() {

    // ==================== 🎯 狀態管理區塊 ====================
    // 使用 mutableStateOf 配合類別成員屬性，讓 Compose 能完美感知狀態轉變
    private var dndSyncState by mutableStateOf(true)
    private var dndAsBedtimeState by mutableStateOf(false)
    private var bedtimeSyncState by mutableStateOf(false)
    private var powerSaveState by mutableStateOf(false)

    // 底層藍牙連線與系統勿擾權限狀態
    private var isConnectedState by mutableStateOf(false)
    private var isDndAllowedState by mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 延遲初始化偏好設定 SharedPreferences
    private val sharedPrefs by lazy {
        requireContext().getSharedPreferences("${requireContext().packageName}_preferences", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化時從本地持久化檔案讀取快取值
        dndSyncState = sharedPrefs.getBoolean("dnd_sync_key", true)
        dndAsBedtimeState = sharedPrefs.getBoolean("dnd_as_bedtime_key", false)
        bedtimeSyncState = sharedPrefs.getBoolean("bedtime_sync_key", false)
        powerSaveState = sharedPrefs.getBoolean("power_save_key", false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, 
        container: ViewGroup?, 
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val context = requireContext()
                // 感知系統深淺色主題，加載對應的 Monet 系統桌布動態色彩
                val colorScheme = if (isSystemInDarkTheme()) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }

                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(), 
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsScreen() {
        // 🎯 核心修復：使用 derivedStateOf 計算衍生狀態，並透過 by 委託
        // 這會改變字節碼結構，防止舊版 Kotlin IR 編譯器在 Column 內聯時崩潰
        val isPowerSaveEnabled by remember {
            derivedStateOf { dndAsBedtimeState || bedtimeSyncState }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ==================== 1. 連線狀態分組 ====================
            CategoryGroup(title = "連線狀態") {
                CardItem(
                    title = "雙端連通狀態", 
                    summary = if (isConnectedState) "已成功連線到手錶" else "未發現配對手錶，請檢查藍牙或 Wear OS App"
                )
            }

            // ==================== 2. 同步設定分組 ====================
            CategoryGroup(title = "同步設定") {
                SwitchItem(
                    title = "同步勿擾模式", 
                    summary = "當手機開啟勿擾時，自動同步至手錶", 
                    checked = dndSyncState
                ) { nextValue ->
                    dndSyncState = nextValue
                    sharedPrefs.edit().putBoolean("dnd_sync_key", nextValue).apply()
                }

                SwitchItem(
                    title = "將勿擾視為就寢模式", 
                    summary = "開啟後，手機進入勿擾時手錶將同步觸發就寢模式", 
                    checked = dndAsBedtimeState
                ) { nextValue ->
                    dndAsBedtimeState = nextValue
                    sharedPrefs.edit().putBoolean("dnd_as_bedtime_key", nextValue).apply()
                }

                SwitchItem(
                    title = "同步就寢模式", 
                    summary = "獨立同步手機與手錶的就寢狀態", 
                    checked = bedtimeSyncState
                ) { nextValue ->
                    bedtimeSyncState = nextValue
                    sharedPrefs.edit().putBoolean("bedtime_sync_key", nextValue).apply()
                }

                SwitchItem(
                    title = "聯動省電模式", 
                    summary = "當上述就寢或勿擾觸發時，自動開啟省電", 
                    checked = if (isPowerSaveEnabled) powerSaveState else false, 
                    enabled = isPowerSaveEnabled
                ) { nextValue ->
                    powerSaveState = nextValue
                    sharedPrefs.edit().putBoolean("power_save_key", nextValue).apply()
                }
            }

            // ==================== 3. 權限管理分組 ====================
            CategoryGroup(title = "權限管理") {
                CardItem(
                    title = "勿擾模式訪問權限", 
                    summary = if (isDndAllowedState) "DND access granted" else "DND access denied"
                ) {
                    if (!checkDNDPermission()) {
                        openDNDPermissionRequest()
                    } else {
                        Toast.makeText(requireContext(), "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ==================== 🧱 封裝 UI 組件區塊 ====================

    @Composable
    fun CategoryGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column {
            Text(
                text = title, 
                fontSize = 14.sp, 
                fontWeight = FontWeight.Bold, 
                color = MaterialTheme.colorScheme.primary, 
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
            Card(
                shape = RoundedCornerShape(16.dp), 
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ), 
                modifier = Modifier.fillMaxWidth(), 
                content = content
            )
        }
    }

    @Composable
    fun SwitchItem(
        title: String, 
        summary: String, 
        checked: Boolean, 
        enabled: Boolean = true, 
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(16.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = title, 
                    fontSize = 16.sp, 
                    fontWeight = FontWeight.SemiBold, 
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary, 
                    fontSize = 13.sp, 
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    @Composable
    fun CardItem(title: String, summary: String, onClick: (() -> Unit)? = null) {
        val modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
        Row(
            modifier = modifier.padding(16.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ==================== 📡 底層藍牙與生命週期業務邏輯 ====================

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
        Wearable.getCapabilityClient(context)
            .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilityInfo ->
                isConnectedState = !capabilityInfo.nodes.isEmpty()
            }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            isConnectedState = !capabilityInfo.nodes.isEmpty()
        }
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        initConnectivityCheck()
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
