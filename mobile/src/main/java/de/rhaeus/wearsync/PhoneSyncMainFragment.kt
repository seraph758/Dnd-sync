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
    private val isCameraAllowedState = mutableStateOf(false) // 📸 相机权限状态
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 勿扰模式连动状态及其下层子开关
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val dndVibrateSwitch = mutableStateOf(false) // 📳 同步时手表是否震动

    // ⏰ 闹钟模块专用配置状态（从旧代码中完美还原）
    private val alarmPkgState = mutableStateOf("com.google.android.deskclock")

    // 注册相机权限回调
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isCameraAllowedState.value = isGranted
        Log.d("WearSync_Main", "📸 相机权限动态申请结果: $isGranted")
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
        
        // 还原闹钟缓存加载
        alarmPkgState.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212))
                            .statusBarsPadding()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "WearSync 控制台",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // 1️⃣ 核心权限管理卡片（通知与相机权限并排放在一起）
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text("系统核心权限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                                    
                                    // 🔔 通知接管权限
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("通知接管服务", fontSize = 15.sp, color = Color.White)
                                            Text(
                                                text = if (isNotificationAllowedState.value) "已授权" else "未授权 (状态同步必备)",
                                                fontSize = 12.sp,
                                                color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                        if (!isNotificationAllowedState.value) {
                                            Button(
                                                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                                            ) {
                                                Text("去授权", fontSize = 12.sp)
                                            }
                                        }
                                    }

                                    Divider(color = Color(0xFF2C2C2C), thickness = 1.dp)

                                    // 📸 相机接管权限
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("相机硬件权限", fontSize = 15.sp, color = Color.White)
                                            Text(
                                                text = if (isCameraAllowedState.value) "已授权" else "未授权 (手表拍照必备)",
                                                fontSize = 12.sp,
                                                color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                        if (!isCameraAllowedState.value) {
                                            Button(
                                                onClick = { requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688))
                                            ) {
                                                Text("授权相机", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // 手表连接状态卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("手表连线状态", fontSize = 16.sp, color = Color.White)
                                    Box(
                                        modifier = Modifier
                                            .background(if (isConnectedState.value) Color(0xFF2E7D32) else Color(0xFFC62828), RoundedCornerShape(20.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (isConnectedState.value) "已连接" else "未就绪", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // 2️⃣ 自动化同步配置中心（包含勿扰主开光和依附子项）
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // A. 勿扰总开关
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("同步手机勿扰状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("开启后，手机状态变更将驱动下层开关同步", fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Switch(
                                            checked = dndMasterSwitch.value,
                                            onCheckedChange = {
                                                dndMasterSwitch.value = it
                                                sp.edit().putBoolean("dnd_master", it).apply()
                                            }
                                        )
                                    }

                                    // 依附于勿扰总开关下的子面板
                                    AnimatedVisibility(visible = dndMasterSwitch.value) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF252525), RoundedCornerShape(12.dp))
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            // 子开关 1：睡眠模式（无障碍高级点击）
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("连动睡眠模式 (无障碍亮屏点击)", fontSize = 14.sp, color = Color.White)
                                                }
                                                Switch(
                                                    checked = wearSleepSwitch.value,
                                                    onCheckedChange = {
                                                        wearSleepSwitch.value = it
                                                        sp.edit().putBoolean("wear_sleep", it).apply()
                                                    }
                                                )
                                            }
                                            
                                            Divider(color = Color(0xFF383838))

                                            // 子开关 2：省电模式（Setting Write 底层修改）
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("连动省电模式 (Setting Write 底层)", fontSize = 14.sp, color = Color.White)
                                                }
                                                Switch(
                                                    checked = wearPowerSavingSwitch.value,
                                                    onCheckedChange = {
                                                        wearPowerSavingSwitch.value = it
                                                        sp.edit().putBoolean("wear_power_saving", it).apply()
                                                    }
                                                )
                                            }

                                            Divider(color = Color(0xFF383838))

                                            // 子开关 3：手表震动提示
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("同步状态时手表震动提示", fontSize = 14.sp, color = Color.White)
                                                }
                                                Switch(
                                                    checked = dndVibrateSwitch.value,
                                                    onCheckedChange = {
                                                        dndVibrateSwitch.value = it
                                                        sp.edit().putBoolean("dnd_vibrate", it).apply()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 3️⃣ ⏰ 闹钟模块配置中心（原封不动从旧代码中完美原样救回！）
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("闹钟高级拦截配置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                                    Text("配置你手机系统自带闹钟的完整包名，实现双向强弹窗控制：", fontSize = 12.sp, color = Color.Gray)

                                    OutlinedTextField(
                                        value = alarmPkgState.value,
                                        onValueChange = {
                                            alarmPkgState.value = it
                                            sp.edit().putString("alarm_pkg", it).apply()
                                        },
                                        label = { Text("目标闹钟应用包名", color = Color.Gray) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF3F51B5),
                                            unfocusedBorderColor = Color.DarkGray
                                        )
                                    )
                                }
                            }

                            // 4️⃣ 相机测试按钮
                            Button(
                                onClick = { triggerDualCameraSync(requireContext()) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("🧪 发送远端相机拉起信令", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun triggerDualCameraSync(context: Context) {
        Thread {
            try {
                Log.d("WearSync_Main", "📸 开始执行手表远端拍照流程...")
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
                Log.d("WearSync_Main", "⚡ 本地 CameraService 启动完毕")
            } catch (e: Exception) {
                Log.e("WearSync_Main", "双向相机拉起逻辑失败", e)
            }
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