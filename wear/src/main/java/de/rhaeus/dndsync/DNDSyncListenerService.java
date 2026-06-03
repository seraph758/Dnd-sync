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

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_WearListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());
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

                // ⚙️ 業務 A：處理勿擾同步
                if ("dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", 1);
                    boolean wearPowerSave = json.optBoolean("wearPowerSave", false);
                    boolean wearVibrate = json.optBoolean("wearVibrate", true);

                    NotificationManager mNotificationManager = (NotificationManager) 
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        
                        // 🔒 【修復問題3的核心防抖機制】
                        // 如果手機發來的勿擾值跟手錶當前的勿擾值完全一樣，直接當作垃圾數據丟棄！
                        // 這樣就能徹底免除「打開手機App手錶跟著震」以及「後台隨機震動」的Bug！
                        if (dndValue == currentFilter) {
                            Log.d(TAG, " 狀態無變更，防抖鎖攔截，拒絕重複執行震動與無障礙。");
                            return; 
                        }

                        // 狀態確實改變了，才放行執行
                        isInternalUpdate = true;
                        mNotificationManager.setInterruptionFilter(dndValue);
                        handler.postDelayed(() -> isInternalUpdate = false, 2000);
                        
                        // 🚀 【修復問題1】只有當狀態真正改變時，才允許執行手錶單次震動與無障礙防吞劇本！
                        if (wearVibrate) {
                            triggerSingleVibration();
                        }

                        // 只有當進入勿擾/睡眠模式時（數值大於1），才去點擊無障礙面板
                        if (wearPowerSave && dndValue > 1) {
                            Log.d(TAG, " 勿擾狀態變更，且開啟了防吞響應，傳輸真實狀態值: " + dndValue + "。觸發輔助功能點擊...");
                            DNDSyncAccessService accessService = DNDSyncAccessService.getSharedInstance();
                            if (accessService != null) {
                                accessService.clickIconAt80Percent(0);   
                                accessService.clickIcon1_2(200);         
                            }
                        }
                    }
                }

                // ⚙️ 業務 B：處理手機鬧鐘聯動
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    
                    if ("ringing".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "⏰ 鬧鐘確認響鈴！激活手錶端持續震動機制。");
                        startLoopVibration();
                    } 
                    else if ("stopped".equalsIgnoreCase(alarmAction)) {
                        Log.d(TAG, "🛑 鬧鐘已在源端終止，解除手錶震動束縛。");
                        stopLoopVibration();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解包處理失敗", e);
            }
        }
    }

    private void startLoopVibration() {
        try {
            if (globalVibrator == null) {
                globalVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (globalVibrator != null && globalVibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000}; 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    globalVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    globalVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "迴圈震動異常", e);
        }
    }

    public static void stopLoopVibration() {
        try {
            if (globalVibrator != null) {
                globalVibrator.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "停震異常", e);
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
