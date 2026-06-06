package de.rhaeus.dndsync

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.camera.remote.RemoteCameraClient
import androidx.camera.remote.RemoteCameraControl
import androidx.camera.remote.RemoteView
import androidx.wear.widget.BoxInsetLayout

class WearCameraActivity : ComponentActivity() {

    private lateinit var remoteCameraClient: RemoteCameraClient
    private var remoteCameraControl: RemoteCameraControl? = null
    private lateinit var remoteView: RemoteView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 建立穿戴端標準滿版沙盒佈局
        val layout = BoxInsetLayout(this)
        setContentView(layout)

        // 1. 初始化遠端即時取景器元件 (渲染手機相機流)
        remoteView = RemoteView(this)
        layout.addView(remoteView)

        // 2. 初始化穿戴跨端聯動客戶端
        remoteCameraClient = RemoteCameraClient(this)

        // 3. 綁定手機端相機流到手錶取景器，並獲取反向控制權
        remoteCameraClient.bindPreview(remoteView) { control ->
            remoteCameraControl = control
        }

        // 4. 原生藍牙快門按鈕
        val button = Button(this).apply {
            text = "拍照"
            setOnClickListener {
                remoteCameraControl?.takePicture { success ->
                    if (success) {
                        // 📸 拍照成功後，精準連帶退出手錶端 App 介面，絕不留殘留
                        cleanUpAndFinish()
                    }
                }
            }
        }
        layout.addView(button)
    }

    private fun cleanUpAndFinish() {
        try {
            // 解除取景器流綁定
            remoteCameraClient.close()
        } catch (e: Exception) {}
        finishAndRemoveTask() // 👈 乾淨利落銷毀任務樹，徹底回退
    }

    override fun onBackPressed() {
        cleanUpAndFinish()
        super.onBackPressed()
    }

    override fun onDestroy() {
        cleanUpAndFinish()
        super.onDestroy()
    }
}
