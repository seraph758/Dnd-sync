package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "WearSync_PhoneListener";
    private static final String UNIVERSAL_SYNC_PATH = "/wear-universal-sync";
    
    // 🔒 硬连锁高压锁：锁死 2 秒防止循环镜像镜像
    public static boolean isInternalUpdate = false;

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.getPath())) {
            try {
                byte[] data = messageEvent.getData();
                if (data == null) return;

                String jsonStr = new String(data, StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(jsonStr);
                
                String sender = json.optString("sender", "");
                String type = json.optString("type", "");

                if ("phone".equalsIgnoreCase(sender)) return;

                // 🎯 手表任何模式改变，回传到手机端一律只且仅修改系统的勿扰模式
                if ("dnd_sync_packet".equalsIgnoreCase(type) || "dnd".equalsIgnoreCase(type)) {
                    int dndValue = json.optInt("dndValue", NotificationManager.INTERRUPTION_FILTER_ALL);
                    
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (mNotificationManager != null) {
                        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
                        if (dndValue != currentFilter) {
                            Log.d(TAG, "📥 收到手表回传信号 -> 安全写入手机系统勿扰，值: " + dndValue);
                            
                            // 启动两秒双向隔绝防护
                            isInternalUpdate = true; 
                            mNotificationManager.setInterruptionFilter(dndValue);
                            
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                isInternalUpdate = false;
                                Log.d(TAG, "🔓 手机端内锁已平稳安全释放");
                            }, 2000);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "手机端数据解析中枢异常", e);
            }
        }
    }
}
