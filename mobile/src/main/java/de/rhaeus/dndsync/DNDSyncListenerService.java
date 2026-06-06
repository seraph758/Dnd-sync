package de.rhaeus.dndsync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

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

                // 核心過濾：不響應手機端自身發出的廣播
                if ("phone".equalsIgnoreCase(sender)) return;

                // 1. 🎯【相機指令中轉站】：精準捕獲手錶發出的拉起、快門、關閉訊號，轉發給本機獨立運作的 CameraService
                if ("camera_control".equalsIgnoreCase(type) || "camera_action".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 監聽到手錶端相機指令: " + action + " -> 正在同步流轉至 CameraService");
                    try {
                        Intent cameraIntent = new Intent(this, CameraService.class);
                        cameraIntent.putExtra("action", action);
                        
                        if ("REQUEST_LAUNCH_CAMERA".equalsIgnoreCase(action)) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                startForegroundService(cameraIntent);
                            } else {
                                startService(cameraIntent);
                            }
                        } else {
                            startService(cameraIntent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "流轉相機控制流發生異常", e);
                    }
                    return;
                }

                // 2. 🎯【鬧鐘代點控制】：處理手錶端全螢幕響鈴窗回傳的掛斷與延後動作
                if ("alarm_control".equalsIgnoreCase(type)) {
                    String action = json.optString("action", "");
                    Log.d(TAG, "📥 遠端代點中心收到手錶端鬧鐘動作要求: " + action);
                    
                    StatusBarNotification sbn = de.rhaeus.dndsync.DNDNotificationService.currentAlarmNotification;

                    if (sbn != null && sbn.getNotification() != null) {
                        Notification notification = sbn.getNotification();
                        if (notification.actions != null) {
                            // 儲存域精準對齊主面板：防止讀取到空白字典
                            SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                            
                            String targetKey = "關鍵字智能匹配";
                            if ("DISMISS".equalsIgnoreCase(action)) {
                                targetKey = prefs.getString("custom_dismiss_action_index", "關鍵字智能匹配");
                            } else {
                                targetKey = prefs.getString("custom_snooze_action_index", "關鍵字智能匹配");
                            }

                            boolean executed = false;
                            Notification.Action[] actions = notification.actions;

                            // 執行多級欄位代點按鈕映射規則
                            if (targetKey.contains("第 1 個")) {
                                if (actions.length >= 1) { actions[0].actionIntent.send(); executed = true; }
                            } else if (targetKey.contains("第 2 個")) {
                                if (actions.length >= 2) { actions[1].actionIntent.send(); executed = true; }
                            } else if (targetKey.contains("第 3 個")) {
                                if (actions.length >= 3) { actions[2].actionIntent.send(); executed = true; }
                            } else if ("自定義輸入關鍵字".equals(targetKey)) {
                                String userKeyword = "DISMISS".equalsIgnoreCase(action) ? 
                                        prefs.getString("custom_dismiss_keyword_input", "") : prefs.getString("custom_snooze_keyword_input", "");
                                if (userKeyword != null && !userKeyword.trim().isEmpty()) {
                                    for (Notification.Action act : actions) {
                                        if (act.title != null) {
                                            String title = act.title.toString().toLowerCase();
                                            if (title.contains(userKeyword.trim().toLowerCase())) {
                                                Log.d(TAG, "🎯 自定義關鍵字匹配成功，代點按鈕: " + act.title);
                                                act.actionIntent.send(); executed = true; break;
                                            }
                                        }
                                    }
                                }
                            }

                            // 兜底方案：關鍵字智能匹配
                            if (!executed && (targetKey.equals("關鍵字智能匹配") || "自定義輸入關鍵字".equals(targetKey))) {
                                for (Notification.Action act : actions) {
                                    if (act.title != null) {
                                        String title = act.title.toString().toLowerCase();
                                        if ("DISMISS".equalsIgnoreCase(action)) {
                                            if (title.contains("停") || title.contains("關") || title.contains("消") || title.contains("結") || title.contains("dis")) {
                                                Log.d(TAG, "🎯 智能匹配停止成功，代點按鈕: " + act.title);
                                                act.actionIntent.send(); executed = true; break;
                                            }
                                        } else {
                                            if (title.contains("延") || title.contains("稍") || title.contains("後") || title.contains("再") || title.contains("snoo")) {
                                                Log.d(TAG, "🎯 智能匹配延後成功，代點按鈕: " + act.title);
                                                act.actionIntent.send(); executed = true; break;
                                            }
                                        }
                                    }
                                }
                            }

                            // 終極防禦代點：若皆未匹配，強制代點第一個按鈕
                            if (!executed && actions.length > 0) {
                                Log.w(TAG, "⚠️ 字典規則未命中，執行首個按鈕兜底發射");
                                actions[0].actionIntent.send();
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ 手機端目前無緩存響鈴中的有效鬧鐘快照。");
                    }
                    return;
                }

                // 3. 處理手錶反向勿擾同步（安全解耦）
                if ("dnd".equalsIgnoreCase(type)) {
                    SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    if (!prefs.getBoolean("dnd_sync_switch", true)) return;

                    int dndValue = json.optInt("dndValue", 1);
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            Log.d(TAG, "📥 收到手錶反向勿擾請求 -> 更新手機系統勿擾狀態");
                            mNotificationManager.setInterruptionFilter(dndValue);
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析手錶回傳指令失敗", e);
            }
        }
    }
}
