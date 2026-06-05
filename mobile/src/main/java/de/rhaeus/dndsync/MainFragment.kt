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
import java.util.Locale

class MainFragment : Fragment() {

    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private val isConnectedState = mutableStateOf(false)
    private val isNotificationAllowedState = mutableStateOf(false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val isDark = isSystemInDarkTheme()
                MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                    MainScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE) }
        
        // 核心修复 4：移除双语混杂，编写国际化自适应文本字典
        val currentLocale = Locale.getDefault().language
        val isZh = currentLocale.equals("zh", ignoreCase = true)

        val txtTitle = if (isZh) "穿戴互联控制中心" else "Wear Sync Control"
        val txtDndTitle = if (isZh) "勿扰同步" else "Dnd Sync"
        val txtDndSub = if (isZh) "保持手机与手錶勿扰状态镜像一致" else "Mirror Do Not Disturb status to watch"
        val txtPowerTitle = if (isZh) "勿扰联动省电模式" else "Link Power Saving Mode"
        val txtPowerSub = if (isZh) "手錶开启勿扰时将联动切换为省电模式" else "Watch enters battery saver when DND is on"
        val txtVibrateTitle = if (isZh) "状态变更时手錶震动" else "Vibrate on Status Sync"
        val txtVibrateSub = if (isZh) "仅在勿扰状态发生同步时手錶震动提示" else "Vibrate watch only during active sync"
        val txtCameraTitle = if (isZh) "智能相机连动开关" else "Smart Camera Remote"
        val txtCameraSub = if (isZh) "允许通过手錶端拉起并遥控拍照" else "Allow watch to wake and trigger camera"
        val txtPkgLabel = if (isZh) "相机目标过滤包名" else "Target Camera Package"

        var dndSync by remember { mutableStateOf(prefs.getBoolean("dnd_sync_switch", true)) }
        var powerSaverResponse by remember { mutableStateOf(prefs.getBoolean("wear_power_save_response", false)) }
        var vibrateOnSync by remember { mutableStateOf(prefs.getBoolean("wear_vibrate_on_sync", true)) }
        var cameraSyncMaster by remember { mutableStateOf(prefs.getBoolean("custom_camera_sync_master_switch", false)) }
        
        // 核心修复 4：默认目标相机包名精简为唯一的 com.oplus.camera
        var cameraPackages by remember { mutableStateOf(prefs.getString("custom_allowed_camera_packages", "com.oplus.camera") ?: "com.oplus.camera") }

        val isConnected by remember { isConnectedState }
        val isNotificationAllowed by remember { isNotificationAllowedState }

        Scaffold(
            topBar = { TopAppBar(title = { Text(txtTitle, fontWeight = FontWeight.Bold) }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 连接状态指示器
                ConnectionStatusCard(isConnected, isNotificationAllowed, isZh)

                // 勿扰同步大类
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SwitchRow(txtDndTitle, txtDndSub, dndSync) {
                            dndSync = it
                            prefs.edit().putBoolean("dnd_sync_switch", it).apply()
                            triggerLazyUiSync(context, prefs)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        SwitchRow(txtPowerTitle, txtPowerSub, powerSaverResponse) {
                            powerSaverResponse = it
                            prefs.edit().putBoolean("wear_power_save_response", it).apply()
                            triggerLazyUiSync(context, prefs)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        SwitchRow(txtVibrateTitle, txtVibrateSub, vibrateOnSync) {
                            vibrateOnSync = it
                            prefs.edit().putBoolean("wear_vibrate_on_sync", it).apply()
                            triggerLazyUiSync(context, prefs)
                        }
                    }
                }
                // 相机联动控制大类
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SwitchRow(txtCameraTitle, txtCameraSub, cameraSyncMaster) {
                            cameraSyncMaster = it
                            prefs.edit().putBoolean("custom_camera_sync_master_switch", it).apply()
                        }
                        if (cameraSyncMaster) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = cameraPackages,
                                onValueChange = {
                                    cameraPackages = it
                                    prefs.edit().putString("custom_allowed_camera_packages", it).apply()
                                },
                                label = { Text(txtPkgLabel) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ConnectionStatusCard(isConnected: Boolean, isNotificationAllowed: Boolean, isZh: Boolean) {
        val statusText = if (isConnected) (if (isZh) "设备已互联" else "Connected") else (if (isZh) "断开连接" else "Disconnected")
        val permText = if (isNotificationAllowed) (if (isZh) "权限已就绪" else "Permission Granted") else (if (isZh) "需要通知监听权限" else "Fix Permission")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (isConnected && isNotificationAllowed) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(statusText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828))
                Text(permText, fontSize = 14.sp, color = Color.Gray)
            }
        }
    }

    @Composable
    fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.0f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 12.sp, color = Color.Gray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    private fun triggerLazyUiSync(context: Context, prefs: SharedPreferences) {
        // 核心修复 2：由手机 UI 设置变动引发的推送，属于非实时状态改变，isRealTimeSync 强制传 false，防止手錶莫名其妙震动
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val currentFilter = manager?.currentInterruptionFilter ?: 1
        val serviceIntent = Intent(context, DNDNotificationService::class.java)
        if (DNDNotificationService.running) {
            val service = DNDNotificationService()
            service.pushDndAndPowerStatusToWear(currentFilter, false)
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
    }

    private fun unregisterConnectivityListener() {}
}
