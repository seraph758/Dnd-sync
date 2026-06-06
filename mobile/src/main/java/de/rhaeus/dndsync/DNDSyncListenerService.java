package de.rhaeus.dndsync;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            if (data == null) return;

            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                if ("phone".equalsIgnoreCase(sender)) return;

                // 🎯 處理手錶端反向傳回的勿擾同步與睡眠連動
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                    
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            Log.d(TAG, "📥 收到手錶反向勿擾同步請求 -> 更新手機系統勿擾狀態");
                            mNotificationManager.setInterruptionFilter(dndValue);
                            
                            // 🎯 核心同步連動：若勿擾狀態改變，呼叫最高權限無障礙系統原語指令連動睡眠模式
                            boolean isDndActive = (dndValue != NotificationManager.INTERRUPTION_FILTER_ALL);
                            executeBedtimeToggleUiMacro(isDndActive);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析手錶回傳數據失敗", e);
            }
        }
    }

    /**
     * 🎯 核心移植與修正：利用系統級指令展開控制台，點擊睡眠模式，穩定度 100%
     */
    private void executeBedtimeToggleUiMacro(boolean targetActive) {
        DNDSyncAccessService serv = DNDSyncAccessService.getSharedInstance();
        if (serv == null) {
            Log.w(TAG, "⚠️ 無障礙服務尚未連接，放棄執行睡眠模式自動連動切換");
            return;
        }

        new Thread(() -> {
            try {
                // 強制點亮手機螢幕
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP , "dndsync:WakeLock");
                wakeLock.acquire(10 * 1000L);

                Thread.sleep(800);
                
                // 🎯 核心修正：拋棄老舊的滑動座標，直接向 Android 核心發送最高級原語指令，強制拉下快捷欄
                serv.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                
                Thread.sleep(1000);
                serv.clickIcon1_2();    // 模擬自動點擊第一行第二個圖標（睡眠模式）
                Thread.sleep(800);
                serv.goBack();          // 模擬返回鍵，收起快捷狀態欄
                
                if (wakeLock.isHeld()) { wakeLock.release(); }
                Log.d(TAG, "✨ 手機端已成功完成睡眠模式無障礙翻轉自動化流程");
            } catch (Exception e) {
                Log.e(TAG, "無障礙巨集流程執行異常中斷", e);
            }
        }).start();
    }

    private void sendExitSignalToWear() {
        new Thread(() -> {
            try {
                JSONObject exitJson = new JSONObject();
                exitJson.put("sender", "phone");
                exitJson.put("type", "alarm");
                exitJson.put("alarmAction", "FORCE_STOP_WEAR_ALARM");
                exitJson.put("timestamp", System.currentTimeMillis());
                byte[] data = exitJson.toString().getBytes(StandardCharsets.UTF_8);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.getId(), UNIVERSAL_SYNC_PATH, data);
                    Log.d(TAG, "📤 [強制退出] 已向手錶端強發 FORCE_STOP_WEAR_ALARM 退出信號");
                }
            } catch (Exception e) {
                Log.e(TAG, "向手錶發送退出信號失敗", e);
            }
        }).start();
    }
}
