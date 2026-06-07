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

class MainFragment : Fragment() {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 勿扰板块状态
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(true)
    private val wearPowerSwitch = mutableStateOf(false)
    private val wearVibrateSwitch = mutableStateOf(true)

    // 闹钟板块状态
    private val alarmMasterSwitch = mutableStateOf(true)
    private val alarmPkgName = mutableStateOf("com.google.android.deskclock")
    private val alarmStopKeyword = mutableStateOf("停止")
    private val alarmSnoozeKeyword = mutableStateOf("延后")

    // 相机板块状态
    private val cameraPkgName = mutableStateOf("com.oplus.camera")

    override fun onCreateView(
        LayoutInflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        loadSettings()
        return ComposeView(requireContext()).apply {
            setContent {
                val colors = ThemeUtils.getColors()
                MaterialTheme(colorScheme = colors) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surface)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Wear Sync 配套管理器",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // 🔍 顶层检查板块
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                StatusRow("通知监听权限", isNotificationAllowedState.value, colors) {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                                Divider(modifier = Modifier.padding(vertical = 8.dp), color = colors.outline.copy(alpha = 0.3f))
                                StatusRow("手表连接状态", isConnectedState.value, colors, null)
                            }
                        }

                        // 1️⃣ 板块一：勿扰模式双向同步
                        SectionTitle("1️⃣ 勿扰模式双向同步", colors)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                SwitchRow("勿扰模式同步总开关", dndMasterSwitch)
                                
                                // 下属依赖逻辑控制
                                val subEnabled = dndMasterSwitch.value
                                SwitchRow(" └─ 手表睡眠模式连动", wearSleepSwitch, subEnabled)
                                SwitchRow(" └─ 手表省电模式连动", wearPowerSwitch, subEnabled)
                                SwitchRow(" └─ 仅在同步时手表震动", wearVibrateSwitch, subEnabled)
                            }
                        }

                        // 2️⃣ 板块二：远端闹钟硬联锁
                        SectionTitle("2️⃣ 远端闹钟硬联锁", colors)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                SwitchRow("闹钟同步总开关", alarmMasterSwitch)
                                if (alarmMasterSwitch.value) {
                                    InputField("自定义闹钟包名", alarmPkgName, colors)
                                    InputField("自定义停止关键字", alarmStopKeyword, colors)
                                    InputField("自定义延后关键字", alarmSnoozeKeyword, colors)
                                }
                            }
                        }

                        // 3️⃣ 板块三：远端相机低延延迟预预览
                        SectionTitle("3️⃣ 远端相机低延迟预预览", colors)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                InputField("相机目标包名", cameraPkgName, colors)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { triggerRemoteWearCamera() },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("主动拉起手表相机", color = colors.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatusRow(title: String, isOk: Boolean, colors: ColorScheme, onClick: (() -> Unit)?) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, fontSize = 14.sp, color = colors.onSurface, fontWeight = FontWeight.Medium)
                Text(
                    text = if (isOk) "正常已就绪" else "未就绪/未连接",
                    fontSize = 12.sp,
                    color = if (isOk) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
            if (onClick != null && !isOk) {
                Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = colors.primary)) {
                    Text("去去去授权", fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    fun SwitchRow(title: String, state: MutableState<Boolean>, enabled: Boolean = true) {
        val finalState = if (!enabled) false else state.value
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 14.sp, alpha = if (enabled) 1f else 0.4f)
            Switch(
                checked = finalState,
                enabled = enabled,
                onCheckedChange = { 
                    state.value = it
                    saveSettings()
                }
            )
        }
    }

    @Composable
    fun InputField(label: String, state: MutableState<String>, colors: ColorScheme) {
        OutlinedTextField(
            value = state.value,
            onValueChange = { 
                state.value = it
                saveSettings()
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outline
            )
        )
    }

    @Composable
    fun SectionTitle(title: String, colors: ColorScheme) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.primary, modifier = Modifier.padding(vertical = 6.dp))
    }

    private fun saveSettings() {
        val sp = requireContext().getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean("dnd_master", dndMasterSwitch.value)
            .putBoolean("wear_sleep", wearSleepSwitch.value)
            .putBoolean("wear_power", wearPowerSwitch.value)
            .putBoolean("wear_vibrate", wearVibrateSwitch.value)
            .putBoolean("alarm_master", alarmMasterSwitch.value)
            .putString("alarm_pkg", alarmPkgName.value)
            .putString("alarm_stop", alarmStopKeyword.value)
            .putString("alarm_snooze", alarmSnoozeKeyword.value)
            .putString("camera_pkg", cameraPkgName.value)
            .apply()
    }

    private fun loadSettings() {
        val sp = requireContext().getSharedPreferences("dnd_sync_settings", Context.MODE_PRIVATE)
        dndMasterSwitch.value = sp.getBoolean("dnd_master", true)
        wearSleepSwitch.value = sp.getBoolean("wear_sleep", true)
        wearPowerSwitch.value = sp.getBoolean("wear_power", false)
        wearVibrateSwitch.value = sp.getBoolean("wear_vibrate", true)
        alarmMasterSwitch.value = sp.getBoolean("alarm_master", true)
        alarmPkgName.value = sp.getString("alarm_pkg", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        alarmStopKeyword.value = sp.getString("alarm_stop", "停止") ?: "停止"
        alarmSnoozeKeyword.value = sp.getString("alarm_snooze", "延后") ?: "延后"
        cameraPkgName.value = sp.getString("camera_pkg", "com.oplus.camera") ?: "com.oplus.camera"
    }

    private fun triggerRemoteWearCamera() {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_control")
                    put("action", "START_CAMERA_UI")
                }
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(requireContext()).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(requireContext()).sendMessage(node.id, "/wear-universal-sync", data)
                }
            } catch (e: Exception) {
                Log.e("WearSync_Main", "主动拉起手表相机失败", e)
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
