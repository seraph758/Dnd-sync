package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    
    // 全域控震器
    private static Vibrator globalVibrator = null;

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

                if ("wear".equalsIgnoreCase(sender)) return;

                // 🎯 擴充亮點：即時在手錶日誌列印出手機 UI 自定義傳過來的所有未知新欄位
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Log.d(TAG, "【WearSync 動態屬性】" + key + " = " + json.get(key));
                }

                // 業務 A：處理勿擾同步
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", false);

                    NotificationManager mNotificationManager = (NotificationManager) 
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            isInternalUpdate = true;
                            mNotificationManager.setInterruptionFilter(dndValue);
                            handler.postDelayed(() -> isInternalUpdate = false, 2000);
                        }
                    }

                    if (wearVibrate) triggerSingleVibration();

                    if (wearPowerSave) {
                        DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                        if (accessService != null) {
                            accessService.clickIconAt80Percent(0);   
                            accessService.clickIcon1_2(200);         
                        }
                    }
                }

                // 業務 B：處理手機鬧鐘聯動與「持續震動層級」
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        boolean hasDismiss = json.optBoolean("hasDismissButton", false);
                        boolean hasSnooze = json.optBoolean("hasSnoozeButton", false);
                        Log.d(TAG, "⏰ 鬧鐘響鈴！手錶預翻譯結果 -> 有關閉按鈕: " + hasDismiss + " | 有小睡按鈕: " + hasSnooze);
                        
                        // 🔥 核心觸發：啟動無限循環持續震動
                        startLoopVibration();
                        
                        // 這裡負責拉起手錶端的控制 Activity UI (UI 您之後可自由調整)
                        // Intent intent = new Intent(this, WearAlarmActivity.class);
                        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        // intent.putExtra("hasDismiss", hasDismiss);
                        // intent.putExtra("hasSnooze", hasSnooze);
                        // startActivity(intent);
                    } 
                    else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "🛑 收到手機通知：鬧鐘已在別處被關閉，手錶奉命緊急停止震動");
                        stopLoopVibration();
                        // 這裡可以發送全域廣播通知手錶 UI 關閉 Activity
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "手錶解析 JSON 失敗", e);
            }
        }
    }

    // 🎯 啟動無限循環持續震動
    private void startLoopVibration() {
        try {
            if (globalVibrator == null) {
                globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                // 震動節奏：停0ms -> 震動1000ms -> 停500ms -> 震動1000ms
                long[] pattern = {0, 1000, 500, 1000}; 
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // 核心：最後一個參數傳入 0，代表從陣列索引0開始【無限循環】
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    globalVibrator.vibrate(pattern, 0);
                }
                Log.d(TAG, "ℹ️ 手錶持續循環震動波形已成功注入底層");
            }
        } catch (Exception e) {
            Log.e(TAG, "啟動持續震動失敗", e);
        }
    }

    // 🎯 終止震動（全域公開方法，手錶 UI 點擊時也能直接調用）
    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel(); // 🎯 核心：物理切斷所有震動波形
                Log.d(TAG, "ℹ️ 手錶震動已成功被 cancel() 終止");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止震動出錯", e);
        }
    }

    private void triggerSingleVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "單次震動異常", e);
        }
    }
}
