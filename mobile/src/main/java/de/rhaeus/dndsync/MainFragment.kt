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
                    var syncCategoryAlarm by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("sync_category_alarm", true)) }
                    var allowedClockPackages by remember(trigger) { 
                        mutableStateOf(sharedPreferences.getString("custom_allowed_clock_packages", "com.google.android.deskclock,com.sec.android.app.clockpackage,com.android.deskclock") ?: "") 
                    }

                    var cameraMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_camera_sync_master_switch", false)) }
                    var allowedCameraPackages by remember(trigger) { 
                        mutableStateOf(sharedPreferences.getString("custom_allowed_camera_packages", "com.android.camera,com.google.android.GoogleCamera,com.sec.android.app.camera") ?: "") 
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
                            text = "Wear Sync Hub / 万能互联中心",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // 状态卡片区
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
                                    Text(text = "Notification Access / 通知读取权限", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = if (isNotificationAllowed) "Granted / 已授权" else "Denied / 未授权 (点击前往)",
                                        fontSize = 13.sp,
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
                                    Text(text = "Watch Connection / 手表连接状态", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = if (isConnected) "Connected / 已连接" else "Disconnected / 未连接",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        // 勿扰控制分区
                        Text(text = "DND & Power Sync / 勿扰与省电控制", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                SwitchRow(title = "Sync DND Status / 启用勿扰同步", summary = "手机勿扰变更时，自动发送信号强制触发表盘下拉点击流", checked = dndSyncMode) { checked ->
                                    sharedPreferences.edit().putBoolean("dnd_sync_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                SwitchRow(title = "Phone Battery Saver Link / 手机端省电连动", summary = "当手机进入省电模式时触发状态变更", checked = phonePowerSaveByLink) { checked ->
                                    sharedPreferences.edit().putBoolean("phone_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                SwitchRow(
                                    title = "Watch Battery Saver / 手表端省电模式响应", 
                                    summary = "依附主开关：开启后，随状态包连动变更手表省电状态", 
                                    checked = wearPowerSaveResponse,
                                    enabled = dndSyncMode
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_response", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                SwitchRow(title = "Vibrate on Sync / 同步时震动反馈", summary = "同步指令成功送达手表时触发短震提示", checked = wearVibrateOnSync) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_vibrate_on_sync", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                            }
                        }

                        // 闹钟控制分区
                        Text(text = "Advanced Alarm Sandbox / 闹钟自动化防漏沙盒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                SwitchRow(title = "Enable Alarm Sync / 启用手机闹钟连动", summary = "允许拦截并向手表透传特定时钟应用的响铃状态", checked = alarmMasterSwitch) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_alarm_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    OutlinedTextField(
                                        value = allowedClockPackages,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                allowedClockPackages = it
                                                sharedPreferences.edit().putString("custom_allowed_clock_packages", it).apply()
                                            }
                                        },
                                        label = { Text("Clock App Packages / 时钟应用包名 (英文逗号隔开)") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    SwitchRow(title = "Sync ALARM Category", summary = "正在响铃的标准手机闹钟", checked = syncCategoryAlarm && alarmMasterSwitch, enabled = alarmMasterSwitch) { checked ->
                                        sharedPreferences.edit().putBoolean("sync_category_alarm", checked).apply()
                                        prefsTrigger.value++
                                    }
                                }
                            }
                        }

                        // 相机控制分区
                        Text(text = "Remote Camera Sandbox / 远端相机画布投射沙盒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                SwitchRow(
                                    title = "Enable Camera Stream / 启用远端相机连动",
                                    summary = "控制手表端图像流通道的开启状态",
                                    checked = cameraMasterSwitch
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_camera_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

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
                                        label = { Text("Camera Packages / 相机应用包名 (英文逗号隔开)") },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { 
                                            if (isConnected) {
                                                sendActiveWakeupToWear(currentContext)
                                                launchLocalCameraByPackage(currentContext, allowedCameraPackages)
                                            } else {
                                                Toast.makeText(currentContext, "Watch disconnected / 手表未连接", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(text = "🚀 Launch Dual-Camera / 主动唤醒并连动打开双端相机", fontWeight = FontWeight.Bold)
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
        val alpha = if (enabled) 1.0f else 0.4f
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha))
                Text(text = summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f * alpha))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    private fun pushDynamicJsonToWear() {
        val context = context ?: return
        val wSave = sharedPreferences.getBoolean("wear_power_save_response", false)
        val wVibrate = sharedPreferences.getBoolean("wear_vibrate_on_sync", true)
        Thread {
            try {
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
                Log.e("WearSync_UI", "Sync failed", e)
            }
        }.start()
    }

    private fun sendActiveWakeupToWear(context: Context) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_control")
                    put("action", "FORCE_WAKEUP_ACTIVITY") 
                    put("timestamp", System.currentTimeMillis())
                }
                val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun launchLocalCameraByPackage(context: Context, cameraPackages: String) {
        activity?.runOnUiThread {
            try {
                val pm = context.packageManager
                var launchIntent: Intent? = null
                val pkgList = cameraPackages.split(",")
                for (pkg in pkgList) {
                    val trimmedPkg = pkg.trim()
                    if (trimmedPkg.isNotEmpty()) {
                        launchIntent = pm.getLaunchIntentForPackage(trimmedPkg)
                        if (launchIntent != null) break
                    }
                }
                if (launchIntent == null) {
                    launchIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("WearSync_CameraUI", "Failed to launch camera app", e)
            }
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
