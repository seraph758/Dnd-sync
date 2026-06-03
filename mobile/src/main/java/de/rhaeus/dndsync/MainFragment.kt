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
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.nio.charset.StandardCharsets

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
                val darkTheme = isSystemInDarkTheme()
                val currentContext = LocalContext.current
                
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

                    var alarmMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false)) }
                    var alarmDismissKeys by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭") ?: "") }
                    var alarmSnoozeKeys by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡") ?: "") }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Wear Sync 萬能互聯中心",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

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
                                        text = if (isConnected) "已連線" else "未連線",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "遠端互聯與同步控制面板", 
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
                                    pushDynamicJsonToWear()
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                
                                SwitchRow(
                                    title = "手機端省電連動",
                                    summary = "當手機進入省電模式時，觸發狀態變更",
                                    checked = phonePowerSaveByLink
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("phone_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                SwitchRow(
                                    title = "手錶端省電模式響應",
                                    summary = "開啟後，若觸發省電，手錶將執行防吞自動模擬點擊劇本",
                                    checked = wearPowerSaveResponse
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_response", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }

                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                SwitchRow(
                                    title = "同步成功時震動反饋",
                                    summary = "當狀態真正發生變更時，手錶本機觸發短震動提示",
                                    checked = wearVibrateOnSync
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_vibrate_on_sync", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                            }
                        }

                        Text(
                            text = "進階級聯：鬧鐘自動化防漏沙盒", 
                            fontSize = 14.sp, 
                            fontWeight = FontWeight.SemiBold, 
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                SwitchRow(
                                    title = "啟用手機鬧鐘同步連動",
                                    summary = "總閘關閉時，後台將拒絕向手錶傳輸任何鬧鐘穿透數據",
                                    checked = alarmMasterSwitch
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_alarm_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }

                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                val secondaryAlpha = if (alarmMasterSwitch) 1.0f else 0.4f
                                
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "鬧鐘應用過濾篩選 (二級選單)",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = secondaryAlpha)
                                    )
                                    Text(
                                        text = if (alarmMasterSwitch) "已動態相容：預設精準匹配谷歌、三星、小米等所有帶時鐘特徵應用。" else "（請先開啟上方總開關）",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * secondaryAlpha)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = alarmDismissKeys,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                alarmDismissKeys = it
                                                sharedPreferences.edit().putString("custom_alarm_dismiss_keys", it).apply()
                                                pushDynamicJsonToWear()
                                            }
                                        },
                                        label = { Text("自定義「關閉」模糊關鍵字字典") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedTextField(
                                        value = alarmSnoozeKeys,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                alarmSnoozeKeys = it
                                                sharedPreferences.edit().putString("custom_alarm_snooze_keys", it).apply()
                                                pushDynamicJsonToWear()
                                            }
                                        },
                                        label = { Text("自定義「小睡/稍後再響」模糊關鍵字字典") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
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
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        registerConnectivityListener()
        // 🔒 【修復問題2、3】砍掉了這裡無腦發送 pushDynamicJsonToWear() 的指令，讓後台保持極靜音
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
    }

    private fun checkNotificationPermission(): Boolean {
        val context = context ?: return false
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val allowed = flat != null && flat.contains(context.packageName)
        if (isNotificationAllowedState.value != allowed) {
            isNotificationAllowedState.value = allowed
        }
        return allowed
    }

    private fun pushDynamicJsonToWear() {
        val context = context ?: return
        val dndSync = sharedPreferences.getBoolean("dnd_sync_switch", true)
        val wSave = sharedPreferences.getBoolean("wear_power_save_response", false)
        val wVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true)

        Thread {
            try {
                // UI 更改觸發的手動推送，嘗試獲取真實勿擾值
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val realDndValue = nm.currentInterruptionFilter

                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "dnd")
                    put("dndValue", realDndValue) 
                    put("wearPowerSave", wSave)
                    put("wearVibrate", wVibrate)
                    put("timestamp", System.currentTimeMillis())
                }

                val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
            } catch (e: Exception) {
                Log.e("WearSync_UI", "手動同步失敗", e)
            }
        }.start()
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
