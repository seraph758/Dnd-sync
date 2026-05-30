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

    // 透過 Compose 響應式狀態，自動監聽硬體連線與系統勿擾權限
    private val isConnectedState = mutableStateOf(false)
    private val isDndAllowedState = mutableStateOf(false)
    
    // 當 SharedPreferences 發生變更時，累加此觸發器以通知 Composable 刷新數據
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
                val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)
                
                // 🎯 修正警告：讓 remember 直接將 prefsTrigger.value 作為 key 綁定
                val sharedPrefsInstance = remember(prefsTrigger.value) { sharedPrefs }
                
                // 當 prefsTrigger 改變時，重新讀取 SharedPreferences 內部的最新狀態
                val dndSync = sharedPrefsInstance.getBoolean("dnd_sync_key", true)
                val dndAsBedtime = sharedPrefsInstance.getBoolean("dnd_as_bedtime_key", false)
                val bedtimeSync = sharedPrefsInstance.getBoolean("bedtime_sync_key", false)
                val powerSave = sharedPrefsInstance.getBoolean("power_save_key", false)

                // 省電模式開關的可用邏輯聯動
                val isPowerSaveEnabled = dndAsBedtime || bedtimeSync

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
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            // 區塊 1：連線狀態
                            CategoryGroup(title = "連線狀態") {
                                CardItem(
                                    title = "雙端連通狀態", 
                                    summary = if (isConnectedState.value) "已成功連線到手錶" else "未發現配對手錶，請檢查藍牙或 Wear OS App"
                                )
                            }

                            // 區塊 2：同步設定
                            CategoryGroup(title = "同步設定") {
                                SwitchItem(title = "同步勿擾模式", summary = "當手機開啟勿擾時，自動同步至手錶", checked = dndSync) { next ->
                                    updatePref("dnd_sync_key", next)
                                }

                                SwitchItem(title = "將勿擾視為就寢模式", summary = "開啟後，手機進入勿擾時手錶將同步觸發就寢模式", checked = dndAsBedtime) { next ->
                                    updatePref("dnd_as_bedtime_key", next)
                                }

                                SwitchItem(title = "同步就寢模式", summary = "獨立同步手機與手錶的就寢狀態", checked = bedtimeSync) { next ->
                                    updatePref("bedtime_sync_key", next)
                                }

                                SwitchItem(
                                    title = "聯動省電模式", 
                                    summary = "當上述就寢或勿擾觸發時，自動開啟省電", 
                                    checked = if (isPowerSaveEnabled) powerSave else false, 
                                    enabled = isPowerSaveEnabled
                                ) { next ->
                                    updatePref("power_save_key", next)
                                }
                            }

                            // 區塊 3：權限管理
                            CategoryGroup(title = "權限管理") {
                                CardItem(
                                    title = "勿擾模式訪問權限", 
                                    summary = if (isDndAllowedState.value) "勿擾模式權限：已獲取" else "勿擾模式權限：未獲取 (點擊前往授權)",
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
        prefsTrigger.value += 1 // 累加觸發器，使 Composable 區塊重新讀取並刷新 UI
    }

    private fun openDNDPermissionRequest(context: Context) {
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

    // =========================================
    // UI 佈局元件 (Composables)
    // =========================================
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

    // =========================================
    // 生命週期與 Wear OS 通訊監聽
    // =========================================
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
