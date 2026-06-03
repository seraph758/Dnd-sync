if ("alarm".equalsIgnoreCase(type)) {
    String alarmAction = json.optString("alarmAction", "");
    if ("ringing".equalsIgnoreCase(alarmAction)) {
        Log.d(TAG, "⏰ 鬧鐘響鈴中... 啟動持續震動並彈出全屏交互UI。");
        startLoopVibration();
        
        // 🚀 彈出全屏鬧鐘 Activity
        Intent dialogIntent = new Intent(this, WearAlarmActivity.class);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(dialogIntent);
        
    } else if ("stopped".equalsIgnoreCase(alarmAction)) {
        Log.d(TAG, "🛑 鬧鐘已終止，解除手錶震動。");
        stopLoopVibration();
    }
}
