package de.rhaeus.dndsync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class MainFragment : Fragment() {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 勿扰模式连动状态
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)

    // 🎯 闹钟模块 3 个核心自定义配置状态（严格对齐原始定义，设置默认值）
    private val alarmPkgState = mutableStateOf("com.google.android.deskclock")
    private val alarmDismissKeyState = mutableStateOf("停止") 
    private val alarmSnoozeKeyState = mutableStateOf("延后") 

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val sp = context.getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE)

        // 读取持久化配置
        dndMasterSwitch.value = sp.getBoolean("dnd_master", true)
        wearSleepSwitch.value = sp.getBoolean("wear_sleep_toggle", false)
        wearPowerSavingSwitch.value = sp.getBoolean("wear_power_saving_toggle", false)
        alarmPkgState.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        alarmDismissKeyState.value = sp.getString("alarm_dismiss_key", "停止") ?: "停止"
        alarmSnoozeKeyState.value = sp.getString("alarm_snooze_key", "延后") ?: "延后"

        return ComposeView(context).apply {
            setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(0xFF6200EE),
                        background = Color(0xFFF5F5F5),
                        surface = Color.White
                    )
                ) {
                    val isNotificationAllowed by remember { isNotificationAllowedState }
                    val isConnected by remember { isConnectedState }

                    var dndMaster by remember { dndMasterSwitch }
                    var wearSleep by remember { wearSleepSwitch }
                    var wearPowerSaving by remember { wearPowerSavingSwitch }
                    
                    // 🎯 界面输入框变量缓冲（完全修正黑底看不见文字的问题）
                    var inputPkg by remember { mutableStateOf(alarmPkgState.value) }
                    var inputDismissKey by remember { mutableStateOf(alarmDismissKeyState.value) }
                    var inputSnoozeKey by remember { mutableStateOf(alarmSnoozeKeyState.value) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 标题
                        Text(
                            text = "Wear Universal Sync",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // 状态展示面板
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("通知监听权限：", fontWeight = FontWeight.Bold, color = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isNotificationAllowed) {
                                        Text("已授予", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                    } else {
                                        Button(
                                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("去授权", fontSize = 12.sp)
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("蓝牙手表连接：", fontWeight = FontWeight.Bold, color = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isConnected) {
                                        Text("已连接", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("未就绪/未连接", color = Color(0xFFF44336), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // ==================== 🌙 勿扰模式联动配置 ====================
                        Text("勿扰联动配置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("启用手机勿扰同步给手表", modifier = Modifier.weight(1f), color = Color.Black)
                                    Switch(checked = dndMaster, onCheckedChange = {
                                        dndMaster = it
                                        sp.edit().putBoolean("dnd_master", it).apply()
                                    })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("连动触发手表睡眠模式 (Bedtime)", modifier = Modifier.weight(1f), color = Color.Black)
                                    Switch(checked = wearSleep, onCheckedChange = {
                                        wearSleep = it
                                        sp.edit().putBoolean("wear_sleep_toggle", it).apply()
                                    })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("连动触发手表省电模式 (PowerSaving)", modifier = Modifier.weight(1f), color = Color.Black)
                                    Switch(checked = wearPowerSaving, onCheckedChange = {
                                        wearPowerSaving = it
                                        sp.edit().putBoolean("wear_power_saving_toggle", it).apply()
                                    })
                                }
                            }
                        }

                        // ==================== ⏰ 闹钟模块自定义配置 ====================
                        Text("系统闹钟自定义匹配", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                
                                // 🎯 框1：目标时钟应用包名
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("目标时钟应用包名:", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray)
                                    OutlinedTextField(
                                        value = inputPkg,
                                        onValueChange = { inputPkg = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.Black,
                                            unfocusedTextColor = Color.Black,
                                            focusedContainerColor = Color(0xFFF9F9F9),
                                            unfocusedContainerColor = Color(0xFFF9F9F9)
                                        ),
                                        singleLine = true
                                    )
                                }

                                // 🎯 框2：停止关键字
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("通知栏「停止」按钮文本关键字:", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray)
                                    OutlinedTextField(
                                        value = inputDismissKey,
                                        onValueChange = { inputDismissKey = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.Black,
                                            unfocusedTextColor = Color.Black,
                                            focusedContainerColor = Color(0xFFF9F9F9),
                                            unfocusedContainerColor = Color(0xFFF9F9F9)
                                        ),
                                        singleLine = true
                                    )
                                }

                                // 🎯 框3：延后关键字
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("通知栏「延后」按钮文本关键字:", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray)
                                    OutlinedTextField(
                                        value = inputSnoozeKey,
                                        onValueChange = { inputSnoozeKey = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.Black,
                                            unfocusedTextColor = Color.Black,
                                            focusedContainerColor = Color(0xFFF9F9F9),
                                            unfocusedContainerColor = Color(0xFFF9F9F9)
                                        ),
                                        singleLine = true
                                    )
                                }

                                // 保存配置按钮
                                Button(
                                    onClick = {
                                        alarmPkgState.value = inputPkg
                                        alarmDismissKeyState.value = inputDismissKey
                                        alarmSnoozeKeyState.value = inputSnoozeKey
                                        sp.edit()
                                            .putString("alarm_pkg", inputPkg)
                                            .putString("alarm_dismiss_key", inputDismissKey)
                                            .putString("alarm_snooze_key", inputSnoozeKey)
                                            .apply()
                                        Log.d("WearSync_Main", "⏰ 策略更新成功 -> 包名: $inputPkg, 停止键: $inputDismissKey, 延后键: $inputSnoozeKey")
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("储存策略配置")
                                }
                            }
                        }

                        // ==================== 📸 双端同步相机 ====================
                        Text("相机镜头远端连动", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Button(
                            onClick = { triggerRemoteCamera() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF))
                        ) {
                            Text("唤醒并开启双端影像同显系统", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun triggerRemoteCamera() {
        Thread {
            try {
                val context = requireContext()
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_action")
                    put("action", "START_CAMERA_UI")
                }
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
                
                val intent = Intent(context, CameraService::class.java).apply { action = "START_CAMERA" }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d("WearSync_Main", "⚡ 本地 CameraService 启动命令投递完毕")
            } catch (e: Exception) {
                Log.e("WearSync_Main", "双向相机拉起逻辑失败", e)
            }
        }.start()
    }

    override fun onResume() { super.onResume(); checkNotificationPermission(); registerConnectivityListener() }
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
