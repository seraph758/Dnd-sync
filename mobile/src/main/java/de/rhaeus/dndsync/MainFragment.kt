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
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
class MainFragment : Fragment() {

    private val isConnectedState = mutableStateOf(false)
    private val isDndAllowedState = mutableStateOf(false)
    private val prefsTrigger = mutableStateOf(0)

    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = requireContext().applicationContext
        sharedPrefs = appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val appContext = requireContext().applicationContext
                val colorScheme = if (isSystemInDarkTheme()) 
                    dynamicDarkColorScheme(appContext) 
                else 
                    dynamicLightColorScheme(appContext)

                // 隱藏 ActionBar（保險）
                LaunchedEffect(Unit) {
                    (requireActivity() as? AppCompatActivity)?.supportActionBar?.hide()
                }

                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {

                            // === 同步設定（最上方）===
                            CategoryGroup(title = "同步設定") {
                                val dndSync = sharedPrefs.getBoolean("dnd_sync_key", true)
                                val dndAsBedtime = sharedPrefs.getBoolean("dnd_as_bedtime_key", false)
                                val bedtimeSync = sharedPrefs.getBoolean("bedtime_sync_key", false)
                                val powerSave = sharedPrefs.getBoolean("power_save_key", false)

                                val isPowerSaveEnabled = dndAsBedtime || bedtimeSync

                                SwitchItem("同步勿擾模式", "當手機開啟勿擾時，自動同步至手錶", dndSync) { updatePref("dnd_sync_key", it) }
                                SwitchItem("將勿擾視為就寢模式", "開啟後，手機進入勿擾時手錶將同步觸發就寢模式", dndAsBedtime) { updatePref("dnd_as_bedtime_key", it) }
                                SwitchItem("同步就寢模式", "獨立同步手機與手錶的就寢狀態", bedtimeSync) { updatePref("bedtime_sync_key", it) }
                                SwitchItem("聯動省電模式", "當上述就寢或勿擾觸發時，自動開啟省電", 
                                    if (isPowerSaveEnabled) powerSave else false, isPowerSaveEnabled) { updatePref("power_save_key", it) }
                            }

                            // 連線狀態
                            CategoryGroup(title = "連線狀態") {
                                CardItem(
                                    "雙端連通狀態",
                                    if (isConnectedState.value) "已成功連線到手錶" else "未發現配對手錶，請檢查藍牙或 Wear OS App"
                                )
                            }

                            // 權限管理
                            CategoryGroup(title = "權限管理") {
                                CardItem(
                                    "勿擾模式訪問權限",
                                    if (isDndAllowedState.value) "通知权限：已獲取" else "通知权限：未獲取 (點擊前往授權)",
                                    onClick = { openDNDPermissionRequest(appContext) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updatePref(key: String, value: Boolean) {
        sharedPrefs.edit().putBoolean(key, value).apply()
        prefsTrigger.value += 1
    }

    private fun openDNDPermissionRequest(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager?.isNotificationPolicyAccessGranted == false) {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "通知权限已獲取，無需重複開啟", Toast.LENGTH_SHORT).show()
        }
    }

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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                content()
            }
        }
    }

    @Composable
    fun SwitchItem(
        title: String, summary: String, checked: Boolean,
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
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                Spacer(Modifier.height(4.dp))
                Text(text = summary, fontSize = 13.sp,
                    color = if(enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
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
                Spacer(Modifier.height(4.dp))
                Text(text = summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ==================== 生命週期 ====================
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
        if (isDndAllowedState.value != allowed) isDndAllowedState.value = allowed
        return allowed
    }

    private fun initConnectivityCheck() {
        val context = context ?: return
        Wearable.getCapabilityClient(context)
            .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilityInfo ->
                val connected = capabilityInfo.nodes.isNotEmpty()
                if (isConnectedState.value != connected) isConnectedState.value = connected
            }
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        initConnectivityCheck()
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            val connected = capabilityInfo.nodes.isNotEmpty()
            if (isConnectedState.value != connected) isConnectedState.value = connected
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