package de.rhaeus.wearsync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import com.google.android.gms.tasks.Tasks

class PhoneSyncMainFragment : Fragment() {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false) 
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // === [AI_SECURITY_FIREWALL: MAIN_FRAGMENT_DND_STATE_DECLARATION] ===
    // 勿扰联动子开关响应式状态声明
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val dndVibrateSwitch = mutableStateOf(false) 
    // === [AI_SECURITY_FIREWALL_END: MAIN_FRAGMENT_DND_STATE_DECLARATION] ===

    // === [AI_SECURITY_FIREWALL: MAIN_FRAGMENT_ALARM_STATE_DECLARATION] ===
    // 闹钟高级拦截、自定义关键字及【新增闹钟同步总开关】状态声明
    private val alarmMasterSwitch = mutableStateOf(true) // ⏰ 闹钟同步总开关状态
    private val alarmPkgState = mutableStateOf("com.google.android.deskclock")
    private val alarmDismissKeyState = mutableStateOf("停止")
    private val alarmSnoozeKeyState = mutableStateOf("延后")
    // === [AI_SECURITY_FIREWALL_END: MAIN_FRAGMENT_ALARM_STATE_DECLARATION] ===

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isCameraAllowedState.value = isGranted
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val sp = requireContext().getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE)
        dndMasterSwitch.value = sp.getBoolean("dnd_master", true)
        wearSleepSwitch.value = sp.getBoolean("wear_sleep", false)
        wearPowerSavingSwitch.value = sp.getBoolean("wear_power_saving", false)
        dndVibrateSwitch.value = sp.getBoolean("dnd_vibrate", false)
        
        // 读取闹钟配置缓存
        alarmMasterSwitch.value = sp.getBoolean("alarm_master", true)
        alarmPkgState.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        alarmDismissKeyState.value = sp.getString("alarm_dismiss_key", "停止") ?: "停止"
        alarmSnoozeKeyState.value = sp.getString("alarm_snooze_key", "延后") ?: "延后"

        // === [AI_SECURITY_FIREWALL: PHONE_REALTIME_SCORE_CALCULATOR] ===
        // 操作开关时，在本地实时进行 Linux 权重分数累加并直接落盘保存
        fun calculateAndSaveMask() {
            var score = 0
            if (wearSleepSwitch.value) score += 1       // 🛌 睡眠模式权重数 = 1
            if (wearPowerSavingSwitch.value) score += 2 // 🔋 省电模式权重数 = 2
            if (dndVibrateSwitch.value) score += 4     // 📳 同步震动权重数 = 4
            sp.edit().putInt("switches_mask", score).apply()
            Log.d("WearSync_Main", "📊 用户拨动开关，本地开关实时组合总分数更新为: $score")
        }
        // === [AI_SECURITY_FIREWALL_END: PHONE_REALTIME_SCORE_CALCULATOR] ===

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding()) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(text = "WearSync 控制台", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)

                            // 核心系统权限卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("系统核心权限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text("通知接管服务", fontSize = 15.sp, color = Color.White)
                                            Text(text = if (isNotificationAllowedState.value) "已授权" else "未授权 (核心状态同步必备)", fontSize = 12.sp, color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336))
                                        }
                                        if (!isNotificationAllowedState.value) {
                                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("去授权") }
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFF2C2C2C))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text("相机硬件权限", fontSize = 15.sp, color = Color.White)
                                            Text(text = if (isCameraAllowedState.value) "已授权" else "未授权 (远程拍照必备)", fontSize = 12.sp, color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336))
                                        }
                                        if (!isCameraAllowedState.value) {
                                            Button(onClick = { requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }) { Text("授权相机") }
                                        }
                                    }
                                }
                            }

                            // 手表通信就绪状态卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("手表连线状态", fontSize = 16.sp, color = Color.White)
                                    Box(modifier = Modifier.background(if (isConnectedState.value) Color(0xFF2E7D32) else Color(0xFFC62828), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                        Text(if (isConnectedState.value) "已连接" else "未就绪", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // === [AI_SECURITY_FIREWALL: MAIN_FRAGMENT_DND_CONFIG_UI] ===
                            // 勿扰自动化组合配置 UI 模块
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("同步勿扰状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("开启后将双向对齐手表状态(支持开启与联动关闭)", fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Switch(checked = dndMasterSwitch.value, onCheckedChange = { dndMasterSwitch.value = it; sp.edit().putBoolean("dnd_master", it).apply() })
                                    }

                                    AnimatedVisibility(visible = dndMasterSwitch.value) {
                                        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("1. 同步状态时手表震动", fontSize = 14.sp, color = Color.White)
                                                Switch(checked = dndVibrateSwitch.value, onCheckedChange = { dndVibrateSwitch.value = it; sp.edit().putBoolean("dnd_vibrate", it).apply(); calculateAndSaveMask() })
                                            }
                                            HorizontalDivider(color = Color(0xFF383838))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("2. 连动睡眠模式", fontSize = 14.sp, color = Color.White)
                                                Switch(checked = wearSleepSwitch.value, onCheckedChange = { wearSleepSwitch.value = it; sp.edit().putBoolean("wear_sleep", it).apply(); calculateAndSaveMask() })
                                            }
                                            HorizontalDivider(color = Color(0xFF383838))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("3. 连动省电模式", fontSize = 14.sp, color = Color.White)
                                                Switch(checked = wearPowerSavingSwitch.value, onCheckedChange = { wearPowerSavingSwitch.value = it; sp.edit().putBoolean("wear_power_saving", it).apply(); calculateAndSaveMask() })
                                            }
                                        }
                                    }
                                }
                            }
                            // === [AI_SECURITY_FIREWALL_END: MAIN_FRAGMENT_DND_CONFIG_UI] ===

                            // === [AI_SECURITY_FIREWALL: MAIN_FRAGMENT_ALARM_CONFIG_UI] ===
                            // ⏰ 闹钟拦截配置模块 (已精准加入 alarmMasterSwitch 联动总开关控制，完美保留原映射动作词)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("同步手机闹钟状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("开启后支持手表端接管非标闹钟的全屏响铃拦截与控制", fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Switch(checked = alarmMasterSwitch.value, onCheckedChange = { alarmMasterSwitch.value = it; sp.edit().putBoolean("alarm_master", it).apply() })
                                    }

                                    AnimatedVisibility(visible = alarmMasterSwitch.value) {
                                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedTextField(
                                                value = alarmPkgState.value,
                                                onValueChange = { alarmPkgState.value = it; sp.edit().putString("alarm_pkg", it).apply() },
                                                label = { Text("目标闹钟应用包名", color = Color.Gray) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF3F51B5), unfocusedBorderColor = Color.DarkGray)
                                            )

                                            OutlinedTextField(
                                                value = alarmDismissKeyState.value,
                                                onValueChange = { alarmDismissKeyState.value = it; sp.edit().putString("alarm_dismiss_key", it).apply() },
                                                label = { Text("自定义“停止/关闭”动作按钮文本匹配词", color = Color.Gray) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.DarkGray)
                                            )

                                            OutlinedTextField(
                                                value = alarmSnoozeKeyState.value,
                                                onValueChange = { alarmSnoozeKeyState.value = it; sp.edit().putString("alarm_snooze_key", it).apply() },
                                                label = { Text("自定义“延后/稍后”动作按钮文本匹配词", color = Color.Gray) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFFFF9800), unfocusedBorderColor = Color.DarkGray)
                                            )
                                        }
                                    }
                                }
                            }
                            // === [AI_SECURITY_FIREWALL_END: MAIN_FRAGMENT_ALARM_CONFIG_UI] ===

                            Button(
                                onClick = { triggerDualCameraSync(requireContext()) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("🧪 调试：拉起远端相机控制", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }

    private fun triggerDualCameraSync(context: Context) {
        Thread {
            try {
                JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_action")
                    put("action", "START_CAMERA_UI")
                }.toString().also { payload ->
                    val data = payload.toByteArray(Charsets.UTF_8)
                    val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                    for (node in nodes) {
                        Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                    }
                }
                val intent = Intent().setClassName("de.rhaeus.wearsync", "de.rhaeus.wearsync.PhoneSyncCameraService")
                intent.action = "START_CAMERA"
                context.startForegroundService(intent)
            } catch (e: Exception) { Log.e("WearSync_Main", "拉起相机异常", e) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        isCameraAllowedState.value = requireContext().checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        registerConnectivityListener()
    }
    override fun onPause() { super.onPause(); unregisterConnectivityListener() }

    private fun checkNotificationPermission() {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        isNotificationAllowedState.value = flat != null && flat.contains(requireContext().packageName)
    }

    private fun registerConnectivityListener() {
        val context = requireContext()
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { isConnectedState.value = it.nodes.isNotEmpty() }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { isConnectedState.value = it.nodes.isNotEmpty() }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        capabilityChangedListener?.let { Wearable.getCapabilityClient(requireContext()).removeListener(it) }
    }
}
