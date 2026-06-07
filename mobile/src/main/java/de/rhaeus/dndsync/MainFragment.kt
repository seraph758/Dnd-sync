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

    // 勿扰板块状态
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val wearVibrateSwitch = mutableStateOf(true)

    // 闹钟板块状态
    private val alarmMasterSwitch = mutableStateOf(true)
    private val alarmPkgState = mutableStateOf("com.google.android.deskclock") // 默认谷歌时钟包名

    // 相机板块状态
    private val cameraPkgState = mutableStateOf("com.oplus.camera") // 🎯 严格锁定默认包名为 com.oplus.camera

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        loadSettings()

        return ComposeView(requireContext()).apply {
            setContent {
                val isNotificationAllowed by isNotificationAllowedState
                val isConnected by isConnectedState

                val dndMaster by dndMasterSwitch
                val wearSleep by wearSleepSwitch
                val wearPowerSaving by wearPowerSavingSwitch
                val wearVibrate by wearVibrateSwitch

                val alarmMaster by alarmMasterSwitch
                var alarmPkg by alarmPkgState
                var cameraPkg by cameraPkgState

                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212))
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 标题栏
                    Text(
                        text = "智能双端同步中心",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 状态卡片
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("通知接听权限: ", color = Color.Gray, fontSize = 14.sp)
                                Text(
                                    text = if (isNotificationAllowed) "已授权" else "未授权 (请点击开启)",
                                    color = if (isNotificationAllowed) Color(0xFF81C784) else Color(0xFFE57373),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("手表连接状态: ", color = Color.Gray, fontSize = 14.sp)
                                Text(
                                    text = if (isConnected) "双端已连接" else "未检测到手表",
                                    color = if (isConnected) Color(0xFF81C784) else Color(0xFFE57373),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            if (!isNotificationAllowed) {
                                Button(
                                    onClick = {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    Text("去授予系统通知特权", color = Color(0xFF121212))
                                }
                            }
                        }
                    }

                    // 1️⃣ 勿扰与高级模式联动卡片
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("1️⃣ 勿扰与手表巨集联动设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("同步总开关", color = Color.White)
                                    Text("手机进入勿扰时联动控制手表", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(checked = dndMaster, onCheckedChange = { dndMasterSwitch.value = it; saveSettings() })
                            }

                            if (dndMaster) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("联动触发手表[睡眠模式]", color = Color.LightGray)
                                    Switch(checked = wearSleep, onCheckedChange = { wearSleepSwitch.value = it; saveSettings() })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("联动触发手表[省电模式]", color = Color.LightGray)
                                    Switch(checked = wearPowerSaving, onCheckedChange = { wearPowerSavingSwitch.value = it; saveSettings() })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("同步时允许手表震动提示", color = Color.LightGray)
                                    Switch(checked = wearVibrate, onCheckedChange = { wearVibrateSwitch.value = it; saveSettings() })
                                }
                            }
                        }
                    }

                    // 2️⃣ 闹钟控制卡片
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("2️⃣ 系统闹钟双端同鸣设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("闹钟同步总开关", color = Color.White)
                                    Text("手机响铃时，手表同步拉起闹钟弹窗", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(checked = alarmMaster, onCheckedChange = { alarmMasterSwitch.value = it; saveSettings() })
                            }

                            if (alarmMaster) {
                                OutlinedTextField(
                                    value = alarmPkg,
                                    onValueChange = { alarmPkgState.value = it; saveSettings() },
                                    label = { Text("目标闹钟应用包名", color = Color.Gray) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFFFB74D),
                                        unfocusedBorderColor = Color.Gray,
                                        focusedLabelColor = Color(0xFFFFB74D),
                                        cursorColor = Color(0xFFFFB74D)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("💡 默认停止和延后。主流手机原生时钟为: com.google.android.deskclock", color = Color(0xFFFFB74D), fontSize = 11.sp)
                            }
                        }
                    }

                    // 3️⃣ 相机控制卡片
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("3️⃣ 远程相机同显设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                            
                            OutlinedTextField(
                                value = cameraPkg,
                                onValueChange = { cameraPkgState.value = it; saveSettings() },
                                label = { Text("本地相机目标包名", color = Color.Gray) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFFB74D),
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor = Color(0xFFFFB74D),
                                    cursorColor = Color(0xFFFFB74D)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("💡 默认已写死锁定为一加/OPPO系统原生相机包名：com.oplus.camera", color = Color.Gray, fontSize = 12.sp)

                            Button(
                                onClick = { triggerLocalCameraServiceTest() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("手动测试拉起本地相机流", color = Color(0xFF121212))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadSettings() {
        val sp = requireContext().getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE)
        dndMasterSwitch.value = sp.getBoolean("dnd_master", true)
        wearSleepSwitch.value = sp.getBoolean("wear_sleep_toggle", false)
        wearPowerSavingSwitch.value = sp.getBoolean("wear_power_toggle", false)
        wearVibrateSwitch.value = sp.getBoolean("wear_vibrate_toggle", true)

        alarmMasterSwitch.value = sp.getBoolean("alarm_master", true)
        alarmPkgState.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        cameraPkgState.value = sp.getString("camera_pkg", "com.oplus.camera") ?: "com.oplus.camera" // 🎯 严格锁定默认值
    }

    private fun saveSettings() {
        val sp = requireContext().getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE)
        sp.edit().apply {
            putBoolean("dnd_master", dndMasterSwitch.value)
            putBoolean("wear_sleep_toggle", wearSleepSwitch.value)
            putBoolean("wear_power_toggle", wearPowerSavingSwitch.value)
            putBoolean("wear_vibrate_toggle", wearVibrateSwitch.value)

            putBoolean("alarm_master", alarmMasterSwitch.value)
            putString("alarm_pkg", alarmPkgState.value)
            putString("camera_pkg", cameraPkgState.value)
            apply()
        }
    }

    private fun triggerLocalCameraServiceTest() {
        Thread {
            try {
                val intent = Intent(requireContext(), CameraService::class.java).apply {
                    action = "START_CAMERA"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
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
