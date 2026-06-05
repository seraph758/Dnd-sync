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

    private val isNotificationAllowedState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val isDark = isSystemInDarkTheme()
                MaterialTheme(
                    colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
                ) {
                    val activity = activity
                    if (activity != null) {
                        val window = activity.window
                        val decorView = window.decorView
                        val wic = WindowInsetsControllerCompat(window, decorView)
                        wic.isAppearanceLightStatusBars = !isDark
                    }

                    val context = LocalContext.current
                    val sharedPref = remember { context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE) }

                    var dndSync by remember { mutableStateOf(sharedPref.getBoolean("dnd_sync_switch", true)) }
                    var sleepModeSync by remember { mutableStateOf(sharedPref.getBoolean("sleep_mode_sync_switch", true)) }
                    var vibrateOnSync by remember { mutableStateOf(sharedPref.getBoolean("vibrate_on_sync_switch", true)) }
                    var alarmSync by remember { mutableStateOf(sharedPref.getBoolean("alarm_sync_switch", true)) }
                    var selectedAlarmType by remember { mutableStateOf(sharedPref.getString("alarm_type_select", "all") ?: "all") }

                    val isAllowed by remember { isNotificationAllowedState }
                    val isConnected by remember { isConnectedState }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp)
                        ) {
                            Text(
                                text = "Wear Sync 穿戴互联",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )

                            // 🎯 状态监控卡片（已转换为简体中文）
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    StatusRow(title = "手表连接状态", isOk = isConnected, okText = "已连接手表", errText = "未检测到手表")
                                    Spacer(modifier = Modifier.height(12.dp))
                                    StatusRow(title = "通知权限状态", isOk = isAllowed, okText = "已授权", errText = "未授权（点击前往）", 
                                        onClick = {
                                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    )
                                }
                            }

                            Text(text = "同步设置", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

                            ToggleItem(title = "同步手机勿扰模式", summary = "手机切换勿扰时，手表自动跟随", checked = dndSync) { checked ->
                                dndSync = checked
                                sharedPref.edit().putBoolean("dnd_sync_switch", checked).apply()
                                triggerLazyUiSync(context, sharedPref)
                            }

                            ToggleItem(title = "自动联动睡眠模式", summary = "勿扰开启时，手表跟随进入睡眠/床头模式", checked = sleepModeSync) { checked ->
                                sleepModeSync = checked
                                sharedPref.edit().putBoolean("sleep_mode_sync_switch", checked).apply()
                                triggerLazyUiSync(context, sharedPref)
                            }

                            ToggleItem(title = "同步成功时手表震动", summary = "接收到发射数据包同步成功后，手表震动一下", checked = vibrateOnSync) { checked ->
                                vibrateOnSync = checked
                                sharedPref.edit().putBoolean("vibrate_on_sync_switch", checked).apply()
                                triggerLazyUiSync(context, sharedPref)
                            }

                            ToggleItem(title = "手机闹钟同步到手表", summary = "手机闹钟响铃时，手表同步显示全屏并震动", checked = alarmSync) { checked ->
                                alarmSync = checked
                                sharedPref.edit().putBoolean("alarm_sync_switch", checked).apply()
                            }

                            if (alarmSync) {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("闹钟拦截范围", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            AlarmTypeButton("全局拦截", selectedAlarmType == "all") {
                                                selectedAlarmType = "all"
                                                sharedPref.edit().putString("alarm_type_select", "all").apply()
                                            }
                                            AlarmTypeButton("仅内置时钟", selectedAlarmType == "system") {
                                                selectedAlarmType = "system"
                                                sharedPref.edit().putString("alarm_type_select", "system").apply()
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { triggerCameraRemoteLaunch(context) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("📸 远程启动手表端相机观景窗", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatusRow(title: String, isOk: Boolean, okText: String, errText: String, onClick: (() -> Unit)? = null) {
        Row(
            modifier = Modifier.fillMaxWidth().run { if (onClick != null) clickable { onClick() } else this },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(text = if (isOk) okText else errText, fontSize = 13.sp, color = if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336))
            }
            Box(modifier = Modifier.size(12.dp).background(color = if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336), shape = RoundedCornerShape(6.dp)))
        }
    }

    @Composable
    fun ToggleItem(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(text = summary, fontSize = 13.sp, color = Color.Gray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    fun AlarmTypeButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(text = text, color = if (isSelected) Color.White else Color.Gray)
        }
    }

    private fun triggerCameraRemoteLaunch(context: Context) {
        Thread {
            try {
                // 🎯 统一底层的全能 JSON 通讯包协议
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "camera_control")
                    put("action", "LAUNCH_WEAR_CAMERA")
                }
                val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
                
                // 🎯 手机本地启动指定的 Oplus 相机应用包名
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.oplus.camera")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "未能在手机上找到 com.oplus.camera 相机应用", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("WearSync_CameraUI", "Failed to launch camera app", e)
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

    // 🎯 完美闭合双参数定义：直接打通底层服务，使界面切换开关能实时把状态推送到手表端！
    private fun triggerLazyUiSync(context: Context, sharedPref: SharedPreferences) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val currentFilter = manager?.currentInterruptionFilter ?: 1
        if (DNDNotificationService.running) {
            val service = DNDNotificationService()
            service.pushDndAndPowerStatusToWear(currentFilter, true)
        }
    }
}
