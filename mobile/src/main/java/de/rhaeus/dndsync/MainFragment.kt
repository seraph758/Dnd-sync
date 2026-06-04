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

                    // --- 勿擾與省電基礎配置讀取 ---
                    var dndSyncMode by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("dnd_sync_switch", true)) }
                    var phonePowerSaveByLink by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("phone_power_save_link", false)) }
                    var wearPowerSaveResponse by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_power_save_response", false)) }
                    var wearVibrateOnSync by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("wear_vibrate_on_sync", true)) }

                    // --- 鬧鐘與時鐘同步配置讀取 ---
                    var alarmMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_alarm_sync_master_switch", false)) }
                    var syncCategoryAlarm by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("sync_category_alarm", true)) }
                    var syncCategoryEvent by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("sync_category_event", false)) }
                    var syncCategoryReminder by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("sync_category_reminder", false)) }
                    var syncCategoryUnknown by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("sync_category_unknown", false)) }
                    var alarmDismissKeys by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭") ?: "") }
                    var alarmSnoozeKeys by remember(trigger) { mutableStateOf(sharedPreferences.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡") ?: "") }
                    
                    // 🌟 1. 時鐘綁定包名
                    var allowedClockPackages by remember(trigger) { 
                        mutableStateOf(sharedPreferences.getString("custom_allowed_clock_packages", "com.google.android.deskclock,com.sec.android.app.clockpackage,com.android.deskclock") ?: "") 
                    }

                    // --- 🌟 萬能相機遙控沙盒配置讀取 ---
                    var cameraMasterSwitch by remember(trigger) { mutableStateOf(sharedPreferences.getBoolean("custom_camera_sync_master_switch", false)) }
                    // 🌟 2. 相機綁定包名
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
                            text = "Wear Sync 萬能互聯中心",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // 權限與連線狀態卡片
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
                                    Text(text = "手機通知存取權限", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = if (isNotificationAllowed) "已授權" else "未授權 (點擊前往)",
                                        fontSize = 14.sp,
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
                                    Text(text = "手錶連線狀態", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = if (isConnected) "已連線" else "未連線",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isConnected) Color(0xFF28A745) else Color(0xFFDC3545)
                                    )
                                }
                            }
                        }

                        // 基礎控制分區
                        Text(text = "遠端互聯與同步控制面板", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                SwitchRow(title = "啟用勿擾狀態同步", summary = "開啟後，手機的勿擾狀態將自動同步發送到手錶", checked = dndSyncMode) { checked ->
                                    sharedPreferences.edit().putBoolean("dnd_sync_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                SwitchRow(title = "手機端省電連動", summary = "當手機進入省電模式時，觸發狀態變更", checked = phonePowerSaveByLink) { checked ->
                                    sharedPreferences.edit().putBoolean("phone_power_save_link", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                
                                // 🎯【優化點 2】手錶省電開關依附於勿擾同步總閘，且只有在對應模式激活時才與狀態一同傳包，不單獨孤立運作
                                SwitchRow(
                                    title = "手錶端省電模式響應", 
                                    summary = "依附於同步機制：開啟後，當同步觸發時才會連動改變手錶省電狀態", 
                                    checked = wearPowerSaveResponse,
                                    enabled = dndSyncMode
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_power_save_response", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                SwitchRow(title = "同步成功時震動反饋", summary = "當狀態真正發生變更時，手錶本機觸發短震動提示", checked = wearVibrateOnSync) { checked ->
                                    sharedPreferences.edit().putBoolean("wear_vibrate_on_sync", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }
                            }
                        }

                        // 鬧鐘控制分區（含包名設定）
                        Text(text = "進階級聯：鬧鐘自動化防漏沙盒", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                SwitchRow(title = "啟用手機鬧鐘同步連動", summary = "總閘關閉時，後台將拒絕向手錶傳輸任何鬧鐘穿透數據", checked = alarmMasterSwitch) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_alarm_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                    pushDynamicJsonToWear()
                                }

                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    // 🌟 綁定時鐘包名輸入框
                                    OutlinedTextField(
                                        value = allowedClockPackages,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                allowedClockPackages = it
                                                sharedPreferences.edit().putString("custom_allowed_clock_packages", it).apply()
                                            }
                                        },
                                        label = { Text("綁定的「時鐘應用包名」白名單 (半角英文逗號隔開)") },
                                        placeholder = { Text("例如: com.android.deskclock") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    SwitchRow(title = "同步 ALARM 類型", summary = "正在響鈴的標準手機鬧鐘", checked = syncCategoryAlarm && alarmMasterSwitch, enabled = alarmMasterSwitch) { checked ->
                                        sharedPreferences.edit().putBoolean("sync_category_alarm", checked).apply()
                                        prefsTrigger.value++
                                    }
                                    SwitchRow(title = "同步 EVENT 類型", summary = "日曆日程，或部分時鐘應用的「即將到來」提前預告", checked = syncCategoryEvent && alarmMasterSwitch, enabled = alarmMasterSwitch) { checked ->
                                        sharedPreferences.edit().putBoolean("sync_category_event", checked).apply()
                                        prefsTrigger.value++
                                    }
                                    SwitchRow(title = "同步 REMINDER 類型", summary = "定時器、倒計時結束或普通的日常提醒通知", checked = syncCategoryReminder && alarmMasterSwitch, enabled = alarmMasterSwitch) { checked ->
                                        sharedPreferences.edit().putBoolean("sync_category_reminder", checked).apply()
                                        prefsTrigger.value++
                                    }
                                    SwitchRow(title = "同步 NONE 未知類型", summary = "相容部分未規範標記時鐘類別的通知", checked = syncCategoryUnknown && alarmMasterSwitch, enabled = alarmMasterSwitch) { checked ->
                                        sharedPreferences.edit().putBoolean("sync_category_unknown", checked).apply()
                                        prefsTrigger.value++
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                    OutlinedTextField(
                                        value = alarmDismissKeys,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                alarmDismissKeys = it
                                                sharedPreferences.edit().putString("custom_alarm_dismiss_keys", it).apply()
                                            }
                                        },
                                        label = { Text("自定義「關閉」模糊關鍵字字典") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = alarmSnoozeKeys,
                                        onValueChange = { 
                                            if (alarmMasterSwitch) {
                                                alarmSnoozeKeys = it
                                                sharedPreferences.edit().putString("custom_alarm_snooze_keys", it).apply()
                                            }
                                        },
                                        label = { Text("自定義「小睡/稍後再響」模糊關鍵字字典") },
                                        enabled = alarmMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // 🌟 相機控制分區
                        Text(text = "進階級聯：遠端相機畫布投射沙盒", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                SwitchRow(
                                    title = "啟用遠端相機畫面傳輸連動",
                                    summary = "總閘關閉時，後台將拒絕手錶端任何相機圖像串流的建立請求",
                                    checked = cameraMasterSwitch
                                ) { checked ->
                                    sharedPreferences.edit().putBoolean("custom_camera_sync_master_switch", checked).apply()
                                    prefsTrigger.value++
                                }

                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Spacer(modifier = Modifier.height(8.dp))
  // 🌟 綁定相機包名輸入框
                                    OutlinedTextField(
                                        value = allowedCameraPackages,
                                        onValueChange = { 
                                            if (cameraMasterSwitch) {
                                                allowedCameraPackages = it
                                                sharedPreferences.edit().putString("custom_allowed_camera_packages", it).apply()
                                            }
                                        },
                                        label = { Text("綁定的「相機應用包名」白名單 (半角英文逗號隔開)") },
                                        placeholder = { Text("例如: com.android.camera") },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // 🌟【優化點 3】雙向主動拉起按鈕
                                    Button(
                                        onClick = { 
                                            if (isConnected) {
                                                // 1. 先執行通知手錶端跳轉
                                                sendActiveWakeupToWear(currentContext)
                                                // 2. 隨後手機端根據輸入框內的包名，立刻強制開啟本地相機應用
                                                launchLocalCameraByPackage(currentContext, allowedCameraPackages)
                                            } else {
                                                Toast.makeText(currentContext, "手錶未連線，無法發送喚醒信號", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = cameraMasterSwitch,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text(text = "🚀 主動喚醒並連動打開雙端相機", fontWeight = FontWeight.Bold)
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
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha))
                Text(text = summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f * alpha))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    // 🎯【優化點 1】此處在發送勿擾同步 JSON 時，底層封裝了當前變更指令時間戳記，以供手錶端搭配殼層指令進行深層睡眠模式喚醒與切換動作
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
                    put("actionTrigger", "TRIGGER_SYSTEM_MODE_SWITCH") // 🎯 傳遞深度同步信號
                    put("timestamp", System.currentTimeMillis())
                }

                val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
            } catch (e: Exception) {
                Log.e("WearSync_UI", "手動同步失敗", e)
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
                
                if (nodes.isEmpty()) {
                    activity?.runOnUiThread { Toast.makeText(context, "找不到可用的手錶節點", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, "/wear-universal-sync", data)
                }
            } catch (e: Exception) {
                Log.e("WearSync_CameraUI", "發送相機喚醒信號失敗", e)
            }
        }.start()
    }    
    
    /**
     * 🎯【優化點 3 核心實現】根據用戶在輸入框中設定的相機包名，在手機端拉起該相機 App
     */
    private fun launchLocalCameraByPackage(context: Context, cameraPackages: String) {
        activity?.runOnUiThread {
            try {
                val pm = context.packageManager
                var launchIntent: Intent? = null
                
                // 如果用戶填寫了多個包名（用逗號隔開），順序嘗試啟動第一個可用的相機
                val pkgList = cameraPackages.split(",")
                for (pkg in pkgList) {
                    val trimmedPkg = pkg.trim()
                    if (trimmedPkg.isNotEmpty()) {
                        launchIntent = pm.getLaunchIntentForPackage(trimmedPkg)
                        if (launchIntent != null) {
                            break
                        }
                    }
                }

                // 如果白名單內找不到，則降級使用系統標準的隱式相機 Intent
                if (launchIntent == null) {
                    launchIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(launchIntent)
                Toast.makeText(context, "已同步拉起手機端相機，開始向手錶投射畫面", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("WearSync_CameraUI", "手機端啟動相機 App 失敗", e)
                Toast.makeText(context, "手機端相機啟動失敗，請確認包名是否正確", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        registerConnectivityListener()
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityListener()
    }

    private fun checkNotificationPermission(): Boolean {
        val context = context ?: return false
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val allowed = flat != null && flat.contains(context.packageName)
        if (isNotificationAllowedState.value != allowed) {
            isNotificationAllowedState.value = allowed
        }
        return allowed
    }

    private fun registerConnectivityListener() {
        val context = context ?: return
        Wearable.getCapabilityClient(context)
            .getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilityInfo ->
                isConnectedState.value = capabilityInfo.nodes.isNotEmpty()
            }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
            isConnectedState.value = capabilityInfo.nodes.isNotEmpty()
        }
        capabilityChangedListener?.let {
            Wearable.getCapabilityClient(context).addListener(it, "dnd_sync")
        }
    }

    private fun unregisterConnectivityListener() {
        val context = context ?: return
        capabilityChangedListener?.let {
            Wearable.getCapabilityClient(context).removeListener(it)
        }
    }
}