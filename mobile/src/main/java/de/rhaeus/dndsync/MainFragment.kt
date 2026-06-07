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
import androidx.compose.material3.Text

class MainFragment : Fragment() {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 勿扰板块状态
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val wearVibrateSwitch = mutableStateOf(false)

    // 闹钟板块状态
    private val alarmMasterSwitch = mutableStateOf(true)
    private val alarmPkgName = mutableStateOf("com.google.android.deskclock")
    private val alarmStopKeywords = mutableStateOf("停止,关闭,dismiss")
    private val alarmSnoozeKeywords = mutableStateOf("稍后提醒, snooze")

    // 相机板块状态
    private val cameraPkgName = mutableStateOf("com.google.android.GoogleCamera")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        loadSettings()

        return ComposeView(requireContext()).apply {
            setContent {
                val isDark = ThemeUtils.isDarkTheme(requireContext())
                val colors = ThemeUtils.getColors(isDark)

                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.background)
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 标题
                            Text(
                                text = "Wear Universal Sync",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // 状态总览卡片
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("手錶連接狀態: ", color = colors.textPrimary, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isConnectedState.value) "已連接" else "未連接",
                                            color = if (isConnectedState.value) Color(0xFF4CAF50) else Color(0xFFF44336),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("通知監聽權限: ", color = colors.textPrimary, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isNotificationAllowedState.value) "已授予" else "未授予",
                                            color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (!isNotificationAllowedState.value) {
                                        Button(
                                            onClick = { openNotificationListenSettings() },
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            Text("去授予權限", color = colors.btnText)
                                        }
                                    }
                                }
                            }

                            // 板块一：勿扰连动设置
                            SectionCard(title = "勿擾模式連動控制", colors = colors) {
                                SwitchRow("啟用手機/手錶雙向勿擾同步", dndMasterSwitch, colors = colors) { saveSettings() }
                                val subEnabled = dndMasterSwitch.value
                                SwitchRow(" └─ 手錶睡眠模式連動", wearSleepSwitch, subEnabled, colors) { saveSettings() }
                                SwitchRow(" └─ 手錶省電模式連動", wearPowerSavingSwitch, subEnabled, colors) { saveSettings() }
                                SwitchRow(" └─ 手錶震動模式連動", wearVibrateSwitch, subEnabled, colors) { saveSettings() }
                            }

                            // 板块二：闹钟硬连锁设置
                            SectionCard(title = "鬧鐘硬連鎖動態配置", colors = colors) {
                                SwitchRow("啟用鬧鐘同步推播", alarmMasterSwitch, colors = colors) { saveSettings() }
                                val alarmEnabled = alarmMasterSwitch.value
                                InputField("手機鬧鐘 App 包名", alarmPkgName, alarmEnabled, colors) { saveSettings() }
                                InputField("停止關鍵字 (逗號隔開)", alarmStopKeywords, alarmEnabled, colors) { saveSettings() }
                                InputField("延後關鍵字 (逗號隔開)", alarmSnoozeKeywords, alarmEnabled, colors) { saveSettings() }
                            }

                            // 板块三：远程相机控制
                            SectionCard(title = "遠程相機同步控制", colors = colors) {
                                InputField("手機相機 App 包名", cameraPkgName, enabled = true, colors = colors) { saveSettings() }
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { triggerRemoteWearCamera() },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("主動拉起手錶端相機介面", color = colors.btnText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SectionCard(title: String, colors: ThemeColors, content: @Composable ColumnScope.() -> Unit) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.accent, modifier = Modifier.padding(bottom = 12.dp))
                content()
            }
        }
    }

    @Composable
    fun SwitchRow(label: String, state: MutableState<Boolean>, enabled: Boolean = true, colors: ThemeColors, onCheckedChange: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4. Bond.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = if (enabled) colors.textPrimary else colors.textSecondary, fontSize = 15.sp)
            Switch(
                checked = state.value,
                enabled = enabled,
                onCheckedChange = { state.value = it; onCheckedChange() },
                colors = SwitchDefaults.colors(checkedThumbColor = colors.accent, checkedTrackColor = colors.accent.copy(alpha = 0.5f))
            )
        }
    }

    @Composable
    fun InputField(label: String, state: MutableState<String>, enabled: Boolean = true, colors: ThemeColors, onValueChange: () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(text = label, color = if (enabled) colors.textPrimary else colors.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            OutlinedTextField(
                value = state.value,
                enabled = enabled,
                onValueChange = { state.value = it; onValueChange() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.textSecondary.copy(alpha = 0.5f),
                    focusedLabelColor = colors.accent
                )
            )
        }
    }

    private fun openNotificationListenSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            Log.e("WearSync_Main", "無法打開通知監聽設置", e)
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
        dndMasterSwitch.value = prefs.getBoolean("dnd_master_switch", true)
        wearSleepSwitch.value = prefs.getBoolean("wear_sleep_switch", false)
        wearPowerSavingSwitch.value = prefs.getBoolean("wear_powersaving_switch", false)
        wearVibrateSwitch.value = prefs.getBoolean("wear_vibrate_switch", false)

        alarmMasterSwitch.value = prefs.getBoolean("alarm_master_switch", true)
        alarmPkgName.value = prefs.getString("alarm_pkg_name", "com.google.android.deskclock") ?: "com.google.android.deskclock"
        alarmStopKeywords.value = prefs.getString("alarm_stop_keywords", "停止,關閉,dismiss") ?: "停止,關閉,dismiss"
        alarmSnoozeKeywords.value = prefs.getString("alarm_snooze_keywords", "稍後提醒, snooze") ?: "稍後提醒, snooze"

        cameraPkgName.value = prefs.getString("camera_pkg_name", "com.google.android.GoogleCamera") ?: "com.google.android.GoogleCamera"
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("dnd_master_switch", dndMasterSwitch.value)
            putBoolean("wear_sleep_switch", wearSleepSwitch.value)
            putBoolean("wear_powersaving_switch", wearPowerSavingSwitch.value)
            putBoolean("wear_vibrate_switch", wearVibrateSwitch.value)
            putBoolean("alarm_master_switch", alarmMasterSwitch.value)
            putString("alarm_pkg_name", alarmPkgName.value)
            putString("alarm_stop_keywords", alarmStopKeywords.value)
            putString("alarm_snooze_keywords", alarmSnoozeKeywords.value)
            putString("camera_pkg_name", cameraPkgName.value)
            apply()
        }
    }

    private fun triggerRemoteWearCamera() {
        Thread {
            try {
                val json = JSONObject()
                json.put("sender", "phone")
                json.put("type", "camera_action")
                json.put("action", "START_CAMERA_UI")
                json.put("timestamp", System.currentTimeMillis())

                val data = json.toString().toByteArray(Charsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(requireContext()).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(requireContext()).sendMessage(node.id, "/wear-universal-sync", data)
                }
            } catch (e: Exception) {
                Log.e("WearSync_Main", "主動拉起手錶相機失敗", e)
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
