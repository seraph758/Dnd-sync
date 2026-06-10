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
                MaterialTheme {
                    var isNotificationAllowed by remember { isNotificationAllowedState }
                    var isCameraAllowed by remember { isCameraAllowedState }
                    var isConnected by remember { isConnectedState }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212))
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "WearSync 控制中心",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // 连线状态卡片
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isConnected) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isConnected) "🟢 手表已连接" else "🔴 未侦测到手表",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            // 权限请求卡片
                            if (!isNotificationAllowed || !isCameraAllowed) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text("需要授权核心权限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)

                                        if (!isNotificationAllowed) {
                                            Button(
                                                onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                            ) {
                                                Text("授权通知拦截权限 (勿扰同步)", color = Color.Black)
                                            }
                                        }

                                        if (!isCameraAllowed) {
                                            Button(
                                                onClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                                            ) {
                                                Text("授权相机权限 (远程取景)")
                                            }
                                        }
                                    }
                                }
                            }

                            // 1️⃣ 勿扰模式同步卡片 (100% 完整保留)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("勿扰状态双向同步", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("开启后，手机切换勿扰模式时，手表将实时同步切换；在手表上操作同样可以反向控制手机。", fontSize = 13.sp, color = Color.LightGray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("状态：", fontSize = 14.sp, color = Color.Gray)
                                        Text("守护服务运行中", fontSize = 14.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            // 2️⃣ 就寝/睡眠模式高级物理同步卡片 (100% 完整保留)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("就寝/睡眠状态硬件同步", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("由于 WearOS 11+ 限制了非系统应用修改就寝模式的 API，系统将通过特权级无障碍辅助功能引擎，在手表端以物理宏指令全自动校准硬件选单状态。", fontSize = 13.sp, color = Color.LightGray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("物理辅助引擎：", fontSize = 14.sp, color = Color.Gray)
                                        Text("已就绪 (高级宏并发保护已开启)", fontSize = 14.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            // 3️⃣ 省电模式同步卡片 (100% 完整保留)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("低电量/省电状态同步", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("当手机进入省电模式或电量低于阈值时，自动激活手表端的对应策略，全面优化整体电池寿命续航极限。", fontSize = 13.sp, color = Color.LightGray)
                                }
                            }

                            // 4️⃣ 快捷磁贴 Tile 同步监控 (100% 完整保留)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("手表快捷磁贴 (Tiles) 绑定", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("您可以长按手表表盘向左滑动，添加 WearSync 专属快捷控制磁贴，无需每次都进应用列表，抬手即可操作双端相机的快速启闭与勿扰管控。", fontSize = 13.sp, color = Color.LightGray)
                                }
                            }

                            // 🎯【优化修改：远程相机多功能双控卡片 - 純簡中 UI】
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("远程相机控制卡片", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        text = "可一键向手表发送唤醒通知。如通讯中断或相机遗留后台，可手动点击强制关闭释放。",
                                        fontSize = 12.sp,
                                        color = Color.LightGray
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // 按钮 1：拉起双端
                                        Button(
                                            onClick = { startCameraWorkflow() },
                                            enabled = isCameraAllowed,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                        ) {
                                            Text("启动双端相机", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }

                                        // 按钮 2：主动终止释放（彻底根治死卡后台问题）
                                        Button(
                                            onClick = { stopCameraServiceOnPhone() },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                                        ) {
                                            Text("强制关闭释放", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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

    private fun startCameraWorkflow() {
        val context = requireContext()
        Thread {
            try {
                PhoneSyncCameraService.sendCameraControlToWatchLive(context, "START_CAMERA")
            } catch (e: Exception) { 
                Log.e("WearSync_Main", "拉起相机异常", e) 
            }
        }.start()
    }

// 🎯【手机端主动安全解锁与释放函数】
    private fun stopCameraServiceOnPhone() {
        try {
            val context = requireContext()
            // 1. 停止手机自身的相机采集前台服务
            val intent = Intent(context, PhoneSyncCameraService::class.java).apply {
                action = "STOP_CAMERA"
            }
            context.startService(intent)
            
            // 2. 向手表同步发送 STOP 指令（如果手表仍开着则会一并通知其 onDestroy 释放）
            Thread {
                try {
                    PhoneSyncCameraService.sendCameraControlToWatchLive(context, "STOP_CAMERA")
                } catch (e: Exception) {
                    Log.e("WearSync_Main", "同步发送停止信令至手表失败", e)
                }
            }.start()
            
            Log.d("WearSync_Main", "🎯 手机端主动触发退出，已向双端管道发送全面销毁释放指令")
        } catch (e: Exception) {
            Log.e("WearSync_Main", "手机主动退出相机服务流程崩溃", e)
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        isCameraAllowedState.value = requireContext().checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        registerConnectivityListener()
    }
    
    override fun onPause() { 
        super.onPause()
        unregisterConnectivityListener() 
    }

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