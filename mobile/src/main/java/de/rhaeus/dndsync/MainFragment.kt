package de.rhaeus.dndsync

import android.app.NotificationManager
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
import android.widget.Toast
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

    @OptIn(ExperimentalMaterial3Api::class)
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

                    // 勿擾控制狀態
                    var dndSyncMaster by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("dnd_sync_switch", true)) }
                    var wearSleepModeLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_sleep_mode_link", true)) }
                    var wearPowerSaveLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_power_save_link", false)) }
                    var wearVibrateOnSync by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_vibrate_on_sync", true)) }

                    // 鬧鐘同步持久化狀態
                    var alarmMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false)) }
                    var allowedClockPackages by remember(trigger) { 
                        mutableStateOf(sharedPreferences.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock") ?: "") 
                    }
                    var alarmEventType by remember(trigger) { mutableStateOf(sharedPreferences.getString("alarm_event_type_select", "ringing") ?: "ringing") }
                    
                    // 停止和延後映射規則
                    var dismissActionConfig by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_dismiss_action_index", "關鍵字智能匹配") ?: "關鍵字智能匹配") }
                    var snoozeActionConfig by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_snooze_action_index", "關鍵字智能匹配") ?: "關鍵字智能匹配") }
                    
                    // 用戶自訂輸入關鍵字內容持久化
                    var customDismissKeyword by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_dismiss_keyword_input", "") ?: "") }
                    var customSnoozeKeyword by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_snooze_keyword_input", "") ?: "") }

                    var dismissExpanded by remember { mutableStateOf(false) }
                    var snoozeExpanded by remember { mutableStateOf(false) }

                    // 相機控制狀態
                    var cameraMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_camera_sync_master_switch", false)) }
                    var allowedCameraPackages by remember(trigger) { 
                        mutableStateOf(sharedPreferences.getString("custom_allowed_camera_packages", "com.oplus.camera") ?: "com.oplus.camera") 
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Wear Sync Hub / 穿戴萬能互聯",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // 系統基礎狀態卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "通知權限監控狀態", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (isNotificationAllowed) "已授權" else "未授權（點擊前往）",
                                        fontSize = 13.sp,
                                        color = if (isNotificationAllowed) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "手錶雙端連接狀態", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (isConnected) "已連接" else "未連接",
                                        fontSize = 13.sp,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        // 1. 同步勿擾板塊
                        Text(text = "勿擾與核心模式同步", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column {
                                SwitchRow(title = "同步勿擾模式總開關", summary = "手機勿擾模式變更時自動向手錶發起射頻包", checked = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("dnd_sync_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SwitchRow(title = "  └─ 勿擾開啟時連動智能睡眠模式", summary = "跟隨手機勿擾通過手錶端無障礙框架觸控激活床頭休眠", checked = wearSleepModeLink && dndSyncMaster, enabled = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_sleep_mode_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SwitchRow(title = "  └─ 勿擾開啟時連動系統省電模式", summary = "跟隨手機勿擾開啟後自動降低手錶系統功耗", checked = wearPowerSaveLink && dndSyncMaster, enabled = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SwitchRow(title = "  └─ 狀態同步切換時手錶同步震動提示", summary = "同步指令投遞成功後，令手錶短暫震動進行物理反饋", checked = wearVibrateOnSync && dndSyncMaster, enabled = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_vibrate_on_sync", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                            }
                        }

                        // 2. 同步鬧鐘板塊
                        Text(text = "鬧鐘自動化防騷擾沙盒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SwitchRow(title = "同步鬧鐘總開關", summary = "攔截手機指定鬧鐘並在手錶端拉起持續喚醒交互窗", checked = alarmMasterSwitch) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_alarm_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = allowedClockPackages,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                allowedClockPackages = it
                                                sharedPreferences.edit().putString("custom_allowed_clock_packages", it).apply()
                                            }
                                        },
                                        label = { Text("用戶自定鬧鐘APP包名 (逗號隔開)") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    
                                    Text("日常鬧鐘監聽事件類型（過濾預告鬧鐘）", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilterButton(text = "響應標準響鈴", isSelected = alarmEventType == "ringing", enabled = alarmMasterSwitch) {
                                            alarmEventType = "ringing"
                                            sharedPreferences.edit().putString("alarm_event_type_select", "ringing").apply()
                                        }
                                        FilterButton(text = "攔截全事件(含預告)", isSelected = alarmEventType == "all_events", enabled = alarmMasterSwitch) {
                                            alarmEventType = "all_events"
                                            sharedPreferences.edit().putString("alarm_event_type_select", "all_events").apply()
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                    
                                     // 停止按鈕映射設置
                                    Text("⌚ 手錶端點擊【停止】對應手機通知的哪個按鈕：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { if (alarmMasterSwitch) dismissExpanded = true },
                                            enabled = alarmMasterSwitch,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(dismissActionConfig)
                                        }
                                        DropdownMenu(expanded = dismissExpanded, onDismissRequest = { dismissExpanded = false }) {
                                            val options = listOf("關鍵字智能匹配", "通知欄第 1 個動作按鈕", "通知欄第 2 個動作按鈕", "通知欄第 3 個動作按鈕", "自定義輸入關鍵字")
                                            options.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        dismissActionConfig = option
                                                        sharedPreferences.edit().putString("custom_dismiss_action_index", option).apply()
                                                        dismissExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    
                                    // 如果停止選項選了自定義，展現帶提示詞的文本框
                                    if (dismissActionConfig == "自定義輸入關鍵字" && alarmMasterSwitch) {
                                        OutlinedTextField(
                                            value = customDismissKeyword,
                                            onValueChange = {
                                                customDismissKeyword = it
                                                sharedPreferences.edit().putString("custom_dismiss_keyword_input", it).apply()
                                            },
                                            placeholder = { Text("這裡填入關鍵字") },
                                            label = { Text("停止關鍵字過濾") },
                                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp)
                                        )
                                    }

                                    // 延後按鈕映射設置
                                    Text("⌚ 手錶端點擊【延後】對應手機通知的哪個按鈕：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { if (alarmMasterSwitch) snoozeExpanded = true },
                                            enabled = alarmMasterSwitch,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(snoozeActionConfig)
                                        }
                                        DropdownMenu(expanded = snoozeExpanded, onDismissRequest = { snoozeExpanded = false }) {
                                            val options = listOf("關鍵字智能匹配", "通知欄第 1 個動作按鈕", "通知欄第 2 個動作按鈕", "通知欄第 3 個動作按鈕", "自定義輸入關鍵字")
                                            options.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        snoozeActionConfig = option
                                                        sharedPreferences.edit().putString("custom_snooze_action_index", option).apply()
                                                        snoozeExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    
                                    // 如果延後選項選了自定義，展現帶提示詞的文本框
                                    if (snoozeActionConfig == "自定義輸入關鍵字" && alarmMasterSwitch) {
                                        OutlinedTextField(
                                            value = customSnoozeKeyword,
                                            onValueChange = {
                                                customSnoozeKeyword = it
                                                sharedPreferences.edit().putString("custom_snooze_keyword_input", it).apply()
                                            },
                                            placeholder = { Text("這裡填入關鍵字") },
                                            label = { Text("延後關鍵字過濾") },
                                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 3. 同步相機板塊
                        Text(text = "遠端相機取景投射沙盒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                SwitchRow(title = "同步相機總開關", summary = "啟用跨端快門傳遞與手錶端取景器調用", checked = cameraMasterSwitch) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_camera_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = allowedCameraPackages,
                                        onValueChange = { 
                                            if (cameraMasterSwitch) {
                                                allowedCameraPackages = it
                                                sharedPreferences.edit().putString("custom_allowed_camera_packages", it).apply()
                                            }
                                        },
                                        label = { Text("自定義相機包名") },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    // 一鍵開啟事件：向手機 Service 端同步調用後台 CameraX 資料流（手機維持隱藏）
                                    Button(
                                        onClick = { 
                                            if (isConnected) {
                                                // 1. 告訴手錶拉起手錶端取景 Activity
                                                sendWakeupSignalToWearCamera(currentContext)
                                                
                                                // 2. 向手機本地服務發送廣播指令，在後台隱蔽開啟相機
                                                Thread {
                                                    try {
                                                        val json = JSONObject().apply {
                                                            put("sender", "phone")
                                                            put("type", "camera_control")
                                                            put("action", "REQUEST_LAUNCH_CAMERA")
                                                            put("timestamp", System.currentTimeMillis())
                                                        }
                                                        val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                                                        val nodes = Tasks.await(Wearable.getNodeClient(currentContext).connectedNodes)
                                                        for (node in nodes) {
                                                            Wearable.getMessageClient(currentContext).sendMessage(node.id, "/wear-universal-sync", data)
                                                        }
                                                    } catch (e: Exception) {}
                                                }.start()
                                            } else {
                                                Toast.makeText(currentContext, "配對手錶未處於連接在線狀態", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                    ) {
                                        Text(text = "📸 一鍵開啟手錶取景器 (手機端保持隱藏)", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
        val widgetAlpha = if (enabled) 1.0f else 0.38f
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = widgetAlpha))
                Text(text = summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * widgetAlpha))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    @Composable
    fun FilterButton(text: String, isSelected: Boolean, enabled: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(text = text, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }

    private fun pushDynamicPreferencesToWear() {
        val context = context ?: return
        val dndMaster = sharedPreferences.getBoolean("dnd_sync_switch", true)
        val wearSleep = sharedPreferences.getBoolean("wear_sleep_mode_link", true)
        val wearPower = sharedPreferences.getBoolean("wear_power_save_link", false)
        val wearVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true)
        
        Thread {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val currentDndState = nm.currentInterruptionFilter
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "dnd")
                    put("dndValue", currentDndState)
                    put("dndSyncMaster", dndMaster)
                    put("wearSleepModeLink", wearSleep)
                    put("wearPowerSave", wearPower)
                    put("vibrateTipsEnable", wearVibrate)
                    put("timestamp", System.currentTimeMillis())
                }
                sendMessageToAllConnectedNodes(context, json.toString())
            } catch (e: Exception) {
                Log.e("WearSync_UI", "勿擾狀態推送失敗", e)
            }
        }.start()
    }

    private fun sendWakeupSignalToWearCamera(context: Context) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_control")
                    put("action", "LAUNCH_WEAR_CAMERA_ACTIVITY")
                    put("timestamp", System.currentTimeMillis())
                }
                sendMessageToAllConnectedNodes(context, json.toString())
            } catch (e: Exception) {}
        }.start()
    }

    private fun launchPhoneLocalCamera(context: Context, packageNames: String) {
        activity?.runOnUiThread {
            try {
                val pm = context.packageManager
                var launchIntent: Intent? = null
                val items = packageNames.split(",")
                for (item in items) {
                    val target = item.trim()
                    if (target.isNotEmpty()) {
                        launchIntent = pm.getLaunchIntentForPackage(target)
                        if (launchIntent != null) break
                    }
                }
                if (launchIntent == null) {
                    launchIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("WearSync_Camera", "拉起手機端本地相機失敗", e)
            }
        }
    }

    private fun sendMessageToAllConnectedNodes(context: Context, message: String) {
        try {
            val data = message.toByteArray(StandardCharsets.UTF_8)
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
            }
        } catch (e: Exception) {
            Log.e("WearSync_Msg", "穿戴數據封包廣播異常", e)
        }
    }

    override fun onResume() { super.onResume(); checkNotificationPermission(); registerConnectivityListener() }
    override fun onPause() { super.onPause(); unregisterConnectivityListener() }

    private fun checkNotificationPermission(): Boolean {
        val context = context ?: return false
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val allowed = flat != null && flat.contains(context.packageName)
        isNotificationAllowedState.value = allowed
        return allowed
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { capabilityInfo -> isConnectedState.value = capabilityInfo.nodes.isNotEmpty() }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo -> isConnectedState.value = capabilityInfo.nodes.isNotEmpty() }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        val context = context ?: return
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).removeListener(it) }
    }
}
                                   