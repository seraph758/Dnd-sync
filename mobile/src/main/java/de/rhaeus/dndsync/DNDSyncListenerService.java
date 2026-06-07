package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) return;
        byte[] data = messageEvent.getData();
        if (data == null) return;

        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String sender = json.optString("sender", "");
            String type = json.optString("type", "");
            String action = json.optString("action", "");

            if ("wear".equalsIgnoreCase(sender)) return; // 攔截自發自收

            Log.d(TAG, "📥 手錶收到通道消息 -> type: " + type + ", action: " + action);

            // 1️⃣ 勿擾與模式連動板塊
            if ("dnd".equalsIgnoreCase(type)) {
                int dndVal = json.optInt("dnd_profile_value", -1);
                if (dndVal != -1) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.setInterruptionFilter(dndVal);
                    }

                    // 💡 修復震動邏輯：讀取開關，開關為 true 時才允許震動
                    boolean wearVibrateToggle = json.optBoolean("wear_vibrate_toggle", true);
                    if (wearVibrateToggle) {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null) {
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    }

                    // 判斷手機是開啟還是關閉勿擾 (INTERRUPTION_FILTER_ALL 為 1，代表關閉勿擾/恢復正常)
                    boolean isDndOn = (dndVal != NotificationManager.INTERRUPTION_FILTER_ALL);
                    
                    // 🎯 啟動核心線程：先強制亮屏並等待，再執行高級控制巨集
                    new Thread(() -> {
                        try {
                            // 1. 強制點亮屏幕（同步鎖定）
                            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null) {
                                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 
                                        "WearSync:WakeLockSync"
                                );
                                wakeLock.acquire(5000); // 鎖定 5 秒確保全流程走完
                                Log.d(TAG, "⚡ 系統已強制點亮手錶屏幕");
                            }
                            
                            // 嚴格等待 1.2 秒讓手錶硬件完全喚醒、屏幕渲染完畢
                            Thread.sleep(1200);

                            DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                            if (accessService != null) {
                                // 使用比 swipeDown 更高級的原生底層方法拉出菜單
                                accessService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                                Log.d(TAG, "⚡ 已通過系統原生命令高級下拉快捷選單");
                                
                                // 等待下拉動畫徹底做完
                                Thread.sleep(1000);

                                if (isDndOn) {
                                    // 手機打開勿擾 -> 手表連動打開睡眠/省電
                                    if (json.optBoolean("wear_sleep_toggle", true)) accessService.triggerBedtimeMacro(true);
                                    Thread.sleep(500); // 錯開巨集執行間隔
                                    if (json.optBoolean("wear_power_toggle", true)) accessService.triggerPowerSavingMacro(true);
                                } else {
                                    // 手機關閉勿擾 -> 手表連動關閉睡眠/省電
                                    if (json.optBoolean("wear_sleep_toggle", true)) accessService.triggerBedtimeMacro(false);
                                    Thread.sleep(500);
                                    if (json.optBoolean("wear_power_toggle", true)) accessService.triggerPowerSavingMacro(false);
                                }
                                
                                Thread.sleep(1000);
                                accessService.goBack(); // 收回面板
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "執行自動化巨線程異常", e);
                        }
                    }).start();
                }
                return;
            }

            // 2️⃣ 鬧鐘模塊響應
            if ("alarm".equalsIgnoreCase(type)) {
                if ("START_ALARM_UI".equalsIgnoreCase(action)) {
                    // 🎯 收到鬧鐘信號，無條件拉起手錶端專屬鬧鐘彈窗
                    Intent uiIntent = new Intent(this, WearAlarmActivity.class);
                    uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(uiIntent);
                    Log.d(TAG, "⏰ 成功拉起手錶端 WearAlarmActivity 界面");
                } else if ("FORCE_STOP_WEAR_ALARM".equalsIgnoreCase(action)) {
                    sendBroadcast(new Intent("de.rhaeus.dndsync.FORCE_STOP_ALARM_UI"));
                }
                return;
            }

            // 3️⃣ 相機喚醒模塊響應
            if ("camera_action".equalsIgnoreCase(type)) {
                if ("START_CAMERA_UI".equalsIgnoreCase(action)) {
                    Intent camIntent = new Intent(this, WearCameraActivity.class);
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(camIntent);
                }
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "手錶解析數據核心異常", e);
        }
    }
}
