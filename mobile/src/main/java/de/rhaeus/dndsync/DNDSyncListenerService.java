package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper; 
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());

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

                // 1. 處理手錶反向同步勿擾
                if ("dnd".equalsIgnoreCase(type)) {
                    int wearDndValue = json.optInt("dndValue", 1);
                    Log.d(TAG, "【簽收手錶勿擾】目標值: " + wearDndValue);

                    NotificationManager mNotificationManager = (NotificationManager) 
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (wearDndValue != currentFilter) {
                            if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                                isInternalUpdate = true;
                                mNotificationManager.setInterruptionFilter(wearDndValue);
                                handler.postDelayed(() -> isInternalUpdate = false, 2000);
                            }
                        }
                    }
                }

                // 2. 處理手錶反向控制鬧鐘指令（關閉 或 小睡）
                if ("alarm".equalsIgnoreCase(type)) {
                    String alarmAction = json.optString("alarmAction", "");
                    Log.d(TAG, "🚨 收到手錶遠端鬧鐘指令: " + alarmAction);
                    
                    StatusBarNotification sbn = DNDNotificationService.currentAlarmNotification;
                    if (sbn == null) {
                        Log.w(TAG, "執行失敗：手機本地當前沒有緩存到任何正在響鈴的鬧鐘");
                        return;
                    }

                    Notification notification = sbn.getNotification();
                    if (notification == null || notification.actions == null) {
                        Log.w(TAG, "執行失敗：緩存的鬧鐘通知中沒有包含任何可控制按鈕");
                        return;
                    }

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    String targetKeywords = "";

                    // 根據手錶點擊的按鈕類型，加載對應的 UI 自定義模糊關鍵字字典
                    if ("dismiss".equalsIgnoreCase(alarmAction)) {
                        targetKeywords = prefs.getString("custom_alarm_dismiss_keys", "关,消,dismiss,stop,关闭").toLowerCase();
                    } else if ("snooze".equalsIgnoreCase(alarmAction)) {
                        targetKeywords = prefs.getString("custom_alarm_snooze_keys", "稍,睡,snooze,稍后,小睡").toLowerCase();
                    }

                    // 開始執行動態模糊匹配並點擊
                    for (Notification.Action action : notification.actions) {
                        if (action.title != null && action.actionIntent != null) {
                            String title = action.title.toString().toLowerCase();
                            
                            for (String keyword : targetKeywords.split(",")) {
                                if (!keyword.isEmpty() && title.contains(keyword)) {
                                    try {
                                        // 🎯 核心射擊：模擬人類點擊該按鈕動作意圖，完美關閉/暫停鬧鐘！
                                        action.actionIntent.send();
                                        Log.d(TAG, "🎉 成功觸發手機通知按鈕 [" + action.title + "]，遠端控制大獲全勝！");
                                        DNDNotificationService.currentAlarmNotification = null; // 清空緩存
                                        return;
                                    } catch (Exception e) {
                                        Log.e(TAG, "觸發通知按鈕意圖失敗", e);
                                    }
                                }
                            }
                        }
                    }
                    Log.w(TAG, "未能在通知中匹配到符合您 UI 設定關鍵字的按鈕動作。");
                }

            } catch (Exception e) {
                Log.e(TAG, "手機解析動態 JSON 失敗", e);
            }
        }
    }
}
