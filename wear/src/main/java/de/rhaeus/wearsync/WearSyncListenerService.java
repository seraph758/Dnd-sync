package de.rhaeus.wearsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    // 手勢內部宏防併發鎖
    private static boolean isGestureMacroRunning = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);

            String sender = json.optString("sender");
            String type = json.optString("type");
            String action = json.optString("action");

            if ("phone".equals(sender)) {
                // 🎯【相機單獨擴展區 - 絕不碰下方任何模塊】
                if ("camera_control".equals(type)) {
                    if ("START_CAMERA".equals(action)) {
                        Log.d(TAG, "📸 收到手機端下發的 START_CAMERA 指令，正在為用戶拉起相機觀景窗...");
                        Intent intent = new Intent(this, WearCameraActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                    } else if ("STOP_CAMERA".equals(action)) {
                        Log.d(TAG, "🛑 收到手機端下發的 STOP_CAMERA 指令，發送廣播命令 Activity 關閉...");
                        Intent stopBroadcast = new Intent(WearCameraActivity.ACTION_STOP_CAMERA_ACTIVITY);
                        sendBroadcast(stopBroadcast);
                    }
                    return; // 相機控制流處理完畢，安全阻斷
                }

                // === 下方為完全保留的原有勿擾同步、狀態對齊與手勢宏邏輯，1個字符都不觸碰 ===
                if ("dnd_sync".equals(type)) {
                    if ("SET_DND".equals(action)) {
                        int state = json.optInt("state", 0);
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) {
                            if (state == 1) {
                                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                                vibrateShort();
                            } else {
                                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                                vibrateShort();
                            }
                            Log.d(TAG, "成功同步手機端的勿擾過濾器狀態為: " + state);
                        }
                    } else if ("TRIGGER_BEDTIME_MACRO".equals(action)) {
                        Log.w(TAG, "⚡ 收到手機端防火牆越級下發的睡眠宏指令，啟動強開模擬！");
                        executePhysicalBedtimeMacro();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析手機端同步信令錯誤", e);
        }
    }

    private void executePhysicalBedtimeMacro() {
        if (isGestureMacroRunning) return;
        new Thread(() -> {
            WearSyncAccessService serv = WearSyncAccessService.getSharedInstance();
            if (serv == null) return;
            PowerManager.WakeLock wakeLock = null;
            try {
                isGestureMacroRunning = true;
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "wearsync:WakeLock");
                    wakeLock.acquire(8000L);
                }
                Thread.sleep(2000);
                serv.swipeDown();
                Thread.sleep(1000);
                serv.clickIcon1_2();
                Thread.sleep(800);
                serv.goBack();
            } catch (InterruptedException e) {
                Log.e(TAG, "手勢宏線程中斷", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                isGestureMacroRunning = false;
            }
        }).start();
    }

    private void vibrateShort() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
