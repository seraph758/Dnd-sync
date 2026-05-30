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

/**
 * 手機端主設定介面 Fragment（Jetpack Compose 頂級視覺完全體）
 * 100% 實現大圓角膠囊卡片外觀，原生支援 Monet 系統動態變色，邏輯完美對齊
 */
class MainFragment : Fragment() {

    // ==================== 🎯 終極修復：將持久化與 UI 聯動狀態提升為類別成員屬性 ====================
    // 這樣 Lambda 閉包只會捕獲 Fragment (this)，不再引發局部變數捕獲的 Kotlin IR 編譯器崩潰 Bug
    private val dndSyncState = mutableStateOf(true)
    private val dndAsBedtimeState = mutableStateOf(false)
    private val bedtimeSyncState = mutableStateOf(false)
    private val powerSaveState = mutableStateOf(false)

    // 底層藍牙與權限狀態
    private val isConnectedState = mutableStateOf(false)
    private val isDndAllowedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 延遲初始化偏好存儲
    private val sharedPrefs by lazy {
        requireContext().getSharedPreferences("${requireContext().packageName}_preferences", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在建立時先從持久化設定中同步初始值
        dndSyncState.value = sharedPrefs.getBoolean("dnd_sync_key", true)
        dndAsBedtimeState.value = sharedPrefs.getBoolean("dnd_as_bedtime_key", false)
        bedtimeSyncState.value = sharedPrefs.getBoolean("bedtime_sync_key", false)
        powerSaveState.value = sharedPrefs.getBoolean("power_save_key", false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val context = requireContext()
                // 自動感知系統主題，加載對應的 Monet 系統桌布動態色彩
                val colorScheme = if (isSystemInDarkTheme()) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }

                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background // 浸染動態背景色
                    ) {
                        SettingsScreen()
                    }
                }
            }
        }
    }

        @Composable
    fun SettingsScreen() {
        // 🎯 精準修改：使用 remember { derivedStateOf { ... } } 代替原本的區域變數
        // 這樣可以改變編譯器生成的 Lambda 捕獲結構，徹底避開 Kotlin IR 內聯編譯崩潰 Bug
        val isPowerSaveEnabled by remember { 
            derivedStateOf { dndAsBedtimeState.value || bedtimeSyncState.value } 
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
                    summary = if (isConnectedState.value) "已成功連線到手錶" else "未發現配對手錶，請檢查藍牙或 Wear OS App"
                )
            }

            // ==================== 2. 同步設定分組 (大圓角膠囊卡片) ====================
            CategoryGroup(title = "同步設定") {
                SwitchItem(
                    title = "同步勿擾模式",
                    summary = "當手機開啟勿擾時，自動同步至手錶",
                    checked = dndSyncState.value,
                    onCheckedChange = { nextValue ->
                        dndSyncState.value = nextValue
                        sharedPrefs.edit().putBoolean("dnd_sync_key", nextValue).apply()
                    }
                )
                SwitchItem(
                    title = "將勿擾視為就寢模式",
                    summary = "開啟後，手機進入勿擾時手錶將同步觸發就寢模式",
                    checked = dndAsBedtimeState.value,
                    onCheckedChange = { nextValue ->
                        dndAsBedtimeState.value = nextValue
                        sharedPrefs.edit().putBoolean("dnd_as_bedtime_key", nextValue).apply()
                    }
                )
                SwitchItem(
                    title = "同步就寢模式",
                    summary = "獨立同步手機與手錶的就寢狀態",
                    checked = bedtimeSyncState.value,
                    onCheckedChange = { nextValue ->
                        bedtimeSyncState.value = nextValue
                        sharedPrefs.edit().putBoolean("bedtime_sync_key", nextValue).apply()
                    }
                )
                SwitchItem(
                    title = "聯動省電模式",
                    summary = "當上述就寢或勿擾觸發時，自動開啟省電",
                    // 🎯 這裡因為使用了 'by' 代理，變數的使用方式（isPowerSaveEnabled）保持原樣，完全不需要改動！
                    checked = if (isPowerSaveEnabled) powerSaveState.value else false,
                    enabled = isPowerSaveEnabled, // 狀態啟用鎖定
                    onCheckedChange = { nextValue ->
                        powerSaveState.value = nextValue
                        sharedPrefs.edit().putBoolean("power_save_key", nextValue).apply()
                    }
                )
            }

            // ==================== 3. 權限管理分組 ====================
            CategoryGroup(title = "權限管理") {
                CardItem(
                    title = "勿擾模式訪問權限",
                    summary = if (isDndAllowedState.value) "DND access granted" else "DND access denied",
                    onClick = {
                        if (!checkDNDPermission()) {
                            openDNDPermissionRequest()
                        } else {
                            Toast.makeText(requireContext(), "勿擾模式權限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }


    // 🧱 封裝組件：自帶大圓角、副標題顯眼化的精緻 M3 膠囊卡片分組容器
    @Composable
    fun CategoryGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, // 自動套用 Monet 主色彩
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) // 半透明微浮起高級色調
                ),
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }

    // 🧱 封裝組件：帶正統 M3 靈魂開關大膠囊的卡片行項目
    @Composable
    fun SwitchItem(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
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
                    color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary, 
                    fontSize = 13.sp, 
                    color = if(enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    // 🧱 封裝組件：支援點擊跳轉或純文字展示的普通項目
    @Composable
    fun CardItem(title: String, summary: String, onClick: (() -> Unit)? = null) {
        val modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
        Row(modifier = modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ==================== 100% 繼承與保留原本的底層生命週期監聽與藍牙業務邏輯 ====================
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
        isDndAllowedState.value = allowed
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
                isConnectedState.value = !capabilityInfo.nodes.isEmpty()
            }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            isConnectedState.value = !capabilityInfo.nodes.isEmpty()
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
