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

class PhoneSyncMainFragment : Fragment() {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false) 
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isCameraAllowedState.value = isGranted
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val isNotificationAllowed by isNotificationAllowedState
                val isCameraAllowed by isCameraAllowedState
                val isConnected by isConnectedState

                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF4F6F9))
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "WearSync 控制台",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1C1E),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // 狀態卡片
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("手錶連接狀態", fontSize = 14.sp, color = Color.Gray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isConnected) "🟢 已連接到 WearOS 設備" else "🔴 未檢測到手錶活躍節點",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                                    )
                                }
                            }

                            // 權限管理卡片
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("核心權限配置", fontSize = 14.sp, color = Color.Gray)

                                    // 通知權限
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("通知接聽權限", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                            Text(if (isNotificationAllowed) "已授權" else "未授權", fontSize = 12.sp, color = if (isNotificationAllowed) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                                        }
                                        if (!isNotificationAllowed) {
                                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                                Text("去授權")
                                            }
                                        }
                                    }

                                    Divider(color = Color(0xFFEEEEEE))

                                    // 相機權限
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("相機硬體權限", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                            Text(if (isCameraAllowed) "已授權" else "未授權", fontSize = 12.sp, color = if (isCameraAllowed) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                                        }
                                        if (!isCameraAllowed) {
                                            Button(onClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }) {
                                                Text("授權相機")
                                            }
                                        }
                                    }

                                    // 🎯 這裡新增：當相機已授權時，展示一鍵手動關閉相機背景服務的紅色按鈕
                                    if (isCameraAllowed) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                try {
                                                    val context = requireContext()
                                                    val svcIntent = Intent(context, PhoneSyncCameraService.class.java)
                                                    svcIntent.action = "STOP_CAMERA"
                                                    context.startService(svcIntent)
                                                    Log.d("PhoneSyncMainFragment", "🎯 用戶點擊主介面按鈕，發送 STOP_CAMERA 關閉服務")
                                                } catch (e: Exception) {
                                                    Log.e("PhoneSyncMainFragment", "關閉相機服務失敗", e)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("❌ 關閉相機背景服務", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) 
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
