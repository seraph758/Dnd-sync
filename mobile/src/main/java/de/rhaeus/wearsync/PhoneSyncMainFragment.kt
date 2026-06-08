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
    private val isCameraAllowedState = mutableStateOf(false) // 📸 相機權限狀態
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    // 勿擾模式連動狀態及其下層子開關
    private val dndMasterSwitch = mutableStateOf(true)
    private val wearSleepSwitch = mutableStateOf(false)
    private val wearPowerSavingSwitch = mutableStateOf(false)
    private val dndVibrateSwitch = mutableStateOf(false) // 📳 同步時手錶是否震動

    // 註冊相機權限回調
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        isCameraAllowedState.value = isGranted
        Log.d("WearSync_Main", "📸 相機權限動態申請結果: $isGranted")
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

                            // 1️⃣ 核心權限管理卡片（通知與相機權限放在一起）
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text("系統核心權限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                                    
                                    // 通知接管權限
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("通知接管服務", fontSize = 15.sp, color = Color.White)
                                            Text(
                                                text = if (isNotificationAllowedState.value) "已授權" else "未授權 (狀態同步必備)",
                                                fontSize = 12.sp,
                                                color = if (isNotificationAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                        if (!isNotificationAllowedState.value) {
                                            Button(
                                                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                                            ) {
                                                Text("去授權", fontSize = 12.sp)
                                            }
                                        }
                                    }

                                    Divider(color = Color(0xFF2C2C2C), thickness = 1.dp)

                                    // 📸 相機接管權限
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("相機硬體權限", fontSize = 15.sp, color = Color.White)
                                            Text(
                                                text = if (isCameraAllowedState.value) "已授權" else "未授權 (手錶拍照必備)",
                                                fontSize = 12.sp,
                                                color = if (isCameraAllowedState.value) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                        if (!isCameraAllowedState.value) {
                                            Button(
                                                onClick = { requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688))
                                            ) {
                                                Text("授權相機", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // 連接狀態卡片
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
                                    Text("手錶連線狀態", fontSize = 16.sp, color = Color.White)
                                    Box(
                                        modifier = Modifier
                                            .background(if (isConnectedState.value) Color(0xFF2E7D32) else Color(0xFFC62828), RoundedCornerShape(20.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (isConnectedState.value) "已連接" else "未就緒", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // 2️⃣ 自動化同步配置中心
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // A. 勿擾總開關
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("同步手機勿擾狀態", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("開啟後，手機狀態變更將驅動下層開關同步", fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Switch(
                                            checked = dndMasterSwitch.value,
                                            onCheckedChange = {
                                                dndMasterSwitch.value = it
                                                sp.edit().putBoolean("dnd_master", it).apply()
                                            }
                                        )
                                    }

                                    // 依附於勿擾總開關下的子面板
                                    AnimatedVisibility(visible = dndMasterSwitch.value) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF252525), RoundedCornerShape(12.dp))
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            // 子開關 1：睡眠模式（無障礙高級點擊）
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("連動睡眠模式 (無障礙亮屏點擊)", fontSize = 14.sp, color = Color.White)
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

                                            // 子開關 2：省電模式（Setting Write 底層修改）
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("連動省電模式 (Setting Write 底層)", fontSize = 14.sp, color = Color.White)
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

                                            // 子開關 3：手錶震動提示
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("同步狀態時手錶震動提示", fontSize = 14.sp, color = Color.White)
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

                            // 3️⃣ 相機測試按鈕
                            Button(
                                onClick = { triggerDualCameraSync(requireContext()) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("🧪 發送遠端相機拉起信令", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                Log.d("WearSync_Main", "📸 開始執行手錶遠端拍照流程...")
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
                Log.d("WearSync_Main", "⚡ 本地 CameraService 啟動完畢")
            } catch (e: Exception) {
                Log.e("WearSync_Main", "雙向相機拉起邏輯失敗", e)
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
