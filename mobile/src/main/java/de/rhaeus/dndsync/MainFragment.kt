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

                    // 1. 勿扰控制状态
                    var dndSyncMaster by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("dnd_sync_switch", true)) }
                    var wearSleepModeLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_sleep_mode_link", true)) }
                    var wearPowerSaveLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_power_save_link", false)) }
                    var wearVibrateOnSync by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_vibrate_on_sync", true)) }

                    // 2. 闹钟同步持久化状态
                    var alarmMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false)) }
                    var allowedClockPackages by remember(trigger) { 
                        mutableStateOf(sharedPreferences.getString("custom_allowed_clock_packages", "com.coloros.alarmclock,com.oplus.camera,com.google.android.deskclock,com.android.deskclock") ?: "") 
                    }
                    var alarmEventType by remember(trigger) { mutableStateOf(sharedPreferences.getString("alarm_event_type_select", "ringing") ?: "ringing") }
                    
                    // 🎯 重新加回：停止和延后动作映射规则的持久化选择
                    var dismissActionConfig by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_dismiss_action_index", "关键字智能匹配") ?: "关键字智能匹配") }
                    var snoozeActionConfig by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_snooze_action_index", "关键字智能匹配") ?: "关键字智能匹配") }

                    var dismissExpanded by remember { mutableStateOf(false) }
                    var snoozeExpanded by remember { mutableStateOf(false) }

                    // 3. 相机控制状态
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
                            text = "Wear Sync Hub / 穿戴万能互联",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // 🎯 系统基础状态卡片
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
                                    Text(text = "通知权限监控状态", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (isNotificationAllowed) "已授权" else "未授权（点击前往）",
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
                                    Text(text = "手表双端连接状态", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (isConnected) "已连接" else "未连接",
                                        fontSize = 13.sp,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        // 🎯 1. 同步勿扰板块
                        Text(text = "勿扰与核心模式同步", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column {
                                SwitchRow(title = "同步勿扰模式总开关", summary = "手机勿扰模式变更时自动向手表发起射频包", checked = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("dnd_sync_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SwitchRow(title = "  └─ 勿扰开启时联动智能睡眠模式", summary = "跟随手机勿扰通过手表端无障碍框架触控激活床头休眠", checked = wearSleepModeLink && dndSyncMaster, enabled = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_sleep_mode_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SwitchRow(title = "  └─ 勿扰开启时联动系统省电模式", summary = "跟随手机勿扰开启后自动降低手表系统功耗", checked = wearPowerSaveLink && dndSyncMaster, enabled = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SwitchRow(title = "  └─ 状态同步切换时手錶同步震动提示", summary = "同步指令投递成功后，令手表短暂震动进行物理反馈", checked = wearVibrateOnSync && dndSyncMaster, enabled = dndSyncMaster) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_vibrate_on_sync", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicPreferencesToWear()
                                }
                            }
                        }

                        // 🎯 2. 同步闹钟板块
                        Text(text = "闹钟自动化防骚扰沙盒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SwitchRow(title = "同步闹钟总开关", summary = "拦截手机指定闹钟并在手表端拉起持续唤醒交互窗", checked = alarmMasterSwitch) { checked ->
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
                                        label = { Text("用户自定闹钟APP包名 (逗号隔开)") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    
                                    Text("日常闹钟监听事件类型（过滤预告闹钟）", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilterButton(text = "响应标准响铃", isSelected = alarmEventType == "ringing", enabled = alarmMasterSwitch) {
                                            alarmEventType = "ringing"
                                            sharedPreferences.edit().putString("alarm_event_type_select", "ringing").apply()
                                        }
                                        FilterButton(text = "拦截全事件(含预告)", isSelected = alarmEventType == "all_events", enabled = alarmMasterSwitch) {
                                            alarmEventType = "all_events"
                                            sharedPreferences.edit().putString("alarm_event_type_select", "all_events").apply()
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                    
                                    // ⚡⚡⚡ 重新加回：停止按钮映射单独设置 ⚡⚡⚡
                                    Text("⌚ 手表端点击【停止】对应手机通知的哪个按钮：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { if (alarmMasterSwitch) dismissExpanded = true },
                                            enabled = alarmMasterSwitch,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(dismissActionConfig)
                                        }
                                        DropdownMenu(expanded = dismissExpanded, onDismissRequest = { dismissExpanded = false }) {
                                            val options = listOf("关键字智能匹配", "通知栏第 1 个动作按钮", "通知栏第 2 个动作按钮", "通知栏第 3 个动作按钮")
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

                                    // ⚡⚡⚡ 重新加回：延后按钮映射单独设置 ⚡⚡⚡
                                    Text("⌚ 手表端点击【延后】对应手机通知的哪个按钮：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { if (alarmMasterSwitch) snoozeExpanded = true },
                                            enabled = alarmMasterSwitch,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(snoozeActionConfig)
                                        }
                                        DropdownMenu(expanded = snoozeExpanded, onDismissRequest = { snoozeExpanded = false }) {
                                            val options = listOf("关键字智能匹配", "通知栏第 1 个动作按钮", "通知栏第 2 个动作按钮", "通知栏第 3 个动作按钮")
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
                                }
                            }
                        }

                        // 🎯 3. 同步相机板块
                        Text(text = "远端相机取景投射沙盒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                SwitchRow(title = "同步相机总开关", summary = "启用跨端快门传递与手表端取景器调用", checked = cameraMasterSwitch) { checked ->
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
                                        label = { Text("自定义相机包名") },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { 
                                            if (isConnected) {
                                                sendWakeupSignalToWearCamera(currentContext)
                                                launchPhoneLocalCamera(currentContext, allowedCameraPackages)
                                            } else {
                                                Toast.makeText(currentContext, "配对手表未处于连接在线状态", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                    ) {
                                        Text(text = "📸 一键打开手机相机与手表取景器", fontWeight = FontWeight.Bold)
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
                Log.e("WearSync_UI", "勿扰状态推送失败", e)
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
                Log.e("WearSync_Camera", "拉起手机端本地相机失败", e)
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
            Log.e("WearSync_Msg", "穿戴数据封包广播异常", e)
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