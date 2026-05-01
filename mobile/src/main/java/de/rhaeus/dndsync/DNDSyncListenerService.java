package de.rhaeus.dndsync;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Looper; 
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class DNDSyncListenerService extends WearableListenerService {
    private static final String TAG = "DNDSyncListenerService";
    private static final String DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync";
    // 新增：用於標記是否為內部觸發的更新
    public static boolean isInternalUpdate = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onMessageReceived (@NonNull MessageEvent messageEvent) {
        Log.d(TAG, "onMessageReceived: " + messageEvent);

        if (messageEvent.getPath().equalsIgnoreCase(DND_SYNC_MESSAGE_PATH)) {
            // ... 解析 data[0] 和 dndStatePhone 的邏輯保持不變 ...
            byte dndStatePhone = messageEvent.getData()[0];
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int filterState = mNotificationManager.getCurrentInterruptionFilter();
            byte currentDndState = (byte) filterState;

            if (dndStatePhone != currentDndState) {
                if (mNotificationManager.isNotificationPolicyAccessGranted()) {
                    
                    // 【核心修改】：在修改前鎖定發送邏輯
                    isInternalUpdate = true;
                    Log.d(TAG, "收到手錶同步請求，鎖定手機回傳邏輯");
                    
                    mNotificationManager.setInterruptionFilter(dndStatePhone);
                    Log.d(TAG, "DND set to " + dndStatePhone);
                    
                    // 2秒後解除鎖定，給系統足夠時間消化狀態變更
                    handler.postDelayed(() -> {
                        isInternalUpdate = false;
                        Log.d(TAG, "內部更新完成，恢復監聽發送");
                    }, 2000);

                } else {
                    Log.d(TAG, "attempting to set DND but access not granted");
                }
            }
        } else {
            super.onMessageReceived(messageEvent);
        }
    }
}

    
