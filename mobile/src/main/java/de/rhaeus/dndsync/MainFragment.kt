package de.rhaeus.dndsync

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MainFragment : Fragment() {
    private val isConnectedState = mutableStateOf(false)
    private val isNotificationAllowedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private lateinit var sharedPrefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val camOk = perms[Manifest.permission.CAMERA] ?: false
        Log.d("WearSync_Main", "相机权限请求结果: $camOk")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        sharedPrefs = requireContext().getSharedPreferences(requireContext().packageName + "_preferences", Context.MODE_PRIVATE)
        
        // 检查并请求权限
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6200EE), background = Color(0xFF121212))) {
                    MainLayout()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainLayout() {
        val context = LocalContext.current
        val isConnected by remember { isConnectedState }
        val isNotifAllowed by remember { isNotificationAllowedState }

        // 板块一：勿扰模式变量
        var dndMaster by remember { mutableStateOf(sharedPrefs.getBoolean("custom_dnd_sync_master_switch", false)) }
        var wearSleepLink by remember { mutableStateOf(sharedPrefs.getBoolean("custom_wear_sleep_mode_link", false)) }
        var wearBatteryLink by remember { mutableStateOf(sharedPrefs.getBoolean("custom_wear_battery_save_link", false)) }
        var vibrateOnSync by remember { mutableStateOf(sharedPrefs.getBoolean("custom_vibrate_on_sync_only", false)) }

        // 板块二：闹钟同步变量
        var alarmMaster by remember { mutableStateOf(sharedPrefs.getBoolean("custom_alarm_sync_master_switch", false)) }
        var alarmPkg by remember { mutableStateOf(sharedPrefs.getString("custom_allowed_clock_packages", "com.google.android.deskclock") ?: "com.google.android.deskclock") }
        var dismissOption by remember { mutableStateOf(sharedPrefs.getString("custom_dismiss_action_index", "智能匹配") ?: "智能匹配") }
        var snoozeOption by remember { mutableStateOf(sharedPrefs.getString("custom_snooze_action_index", "智能匹配") ?: "智能匹配") }

        // 板块三：相机同步变量
        var cameraPkg by remember { mutableStateOf(sharedPrefs.getString("custom_camera_package_name", "com.oplus.camera") ?: "com.oplus.camera") }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Wear Universal Sync 控制台", fontSize = 20.sp, fontWeight = FontWeight.Bold) }) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 连接状态卡片
                Card(colors = CardDefaults.cardColors(containerColor = if (isConnected) Color(0xFF1E351E) else Color(0xFF351E1E))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("手表连接状态", fontWeight = FontWeight.Medium)
                        Text(if (isConnected) "已连接" else "未连接", color = if (isConnected) Color.Green else Color.Red, fontWeight = FontWeight.Bold)
                    }
                }

                // 权限检查卡片
                if (!isNotifAllowed) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A00)), modifier = Modifier.clickable {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开设置，请手动开启通知监听权限", Toast.LENGTH_LONG).show()
                        }
                    }) {
                        Text("⚠️ 点击去授予通知监听服务权限（必须开启）", modifier = Modifier.padding(16.dp), color = Color.Yellow, fontSize = 14.sp)
                    }
                }

                // ==================== 1️⃣ 板块一：勿扰模式双向同步 ====================
                Text("1️⃣ 勿扰与模式同步板块", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFBB86FC))
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, alignment = Alignment.CenterVertically) {
                            Text("勿扰模式同步总开关", fontWeight = FontWeight.SemiBold)
                            Switch(checked = dndMaster, onCheckedChange = {
                                dndMaster = it
                                sharedPrefs.edit().putBoolean("custom_dnd_sync_master_switch", it).apply()
                                if (!it) {
                                    wearSleepLink = false
                                    wearBatteryLink = false
                                    vibrateOnSync = false
                                    sharedPrefs.edit().putBoolean("custom_wear_sleep_mode_link", false)
                                        .putBoolean("custom_wear_battery_save_link", false)
                                        .putBoolean("custom_vibrate_on_sync_only", false).apply()
                                }
                            })
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 依赖项 1：手表睡眠模式
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, alignment = Alignment.CenterVertically) {
                            Text(" ↳ 联动手表睡眠模式", color = if (dndMaster) Color.Unspecified else Color.Gray)
                            Switch(checked = wearSleepLink, enabled = dndMaster, onCheckedChange = {
                                wearSleepLink = it
                                sharedPrefs.edit().putBoolean("custom_wear_sleep_mode_link", it).apply()
                            })
                        }

                        // 依赖项 2：手表省电模式
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, alignment = Alignment.CenterVertically) {
                            Text(" ↳ ↳ 联动手表省电模式", color = if (dndMaster) Color.Unspecified else Color.Gray)
                            Switch(checked = wearBatteryLink, enabled = dndMaster, onCheckedChange = {
                                wearBatteryLink = it
                                sharedPrefs.edit().putBoolean("custom_wear_battery_save_link", it).apply()
                            })
                        }

                        // 依赖项 3：专属单向同步震动
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, alignment = Alignment.CenterVertically) {
                            Text(" ↳ ↳ ↳ 仅在手机向手表同步勿扰时震动", color = if (dndMaster) Color.Unspecified else Color.Gray)
                            Switch(checked = vibrateOnSync, enabled = dndMaster, onCheckedChange = {
                                vibrateOnSync = it
                                sharedPrefs.edit().putBoolean("custom_vibrate_on_sync_only", it).apply()
                            })
                        }
                    }
                }

                // ==================== 2️⃣ 板块二：远端闹钟硬联锁 ====================
                Text("2️⃣ 远端闹钟硬联锁板块", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFBB86FC))
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, alignment = Alignment.CenterVertically) {
                            Text("闹钟同步总开关", fontWeight = FontWeight.SemiBold)
                            Switch(checked = alarmMaster, onCheckedChange = {
                                alarmMaster = it
                                sharedPrefs.edit().putBoolean("custom_alarm_sync_master_switch", it).apply()
                            })
                        }

                        OutlinedTextField(
                            value = alarmPkg,
                            enabled = alarmMaster,
                            onValueChange = { alarmPkg = it; sharedPrefs.edit().putString("custom_allowed_clock_packages", it).apply() },
                            label = { Text("监听闹钟包名（逗号分隔）") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = dismissOption,
                            enabled = alarmMaster,
                            onValueChange = { dismissOption = it; sharedPrefs.edit().putString("custom_dismiss_action_index", it).apply() },
                            label = { Text("停止闹钟按钮定位（智能匹配 或 自定义数字）") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = snoozeOption,
                            enabled = alarmMaster,
                            onValueChange = { snoozeOption = it; sharedPrefs.edit().putString("custom_snooze_action_index", it).apply() },
                            label = { Text("延后闹钟按钮定位（智能匹配 或 自定义数字）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ==================== 3️⃣ 板块三：远端相机预预览 ====================
                Text("3️⃣ 远端相机低延迟预览", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFBB86FC))
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = cameraPkg,
                            onValueChange = { cameraPkg = it; sharedPrefs.edit().putString("custom_camera_package_name", it).apply() },
                            label = { Text("自定义相机包名") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = { sendPullUpCommandToWear(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC6))
                        ) {
                            Text("📲 主动拉起手表端相机 Active", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    private fun sendPullUpCommandToWear(context: Context) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_control")
                    put("action", "FORCE_LAUNCH_WEAR_CAMERA")
                    put("timestamp", System.currentTimeMillis())
                }
                val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
                Log.d("WearSync_Main", "📤 界面层主控成功发往穿戴端: $json")
            } catch (e: Exception) {
                Log.e("WearSync_Main", "主动远程唤醒手表相机崩溃", e)
            }
        }.start()
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
