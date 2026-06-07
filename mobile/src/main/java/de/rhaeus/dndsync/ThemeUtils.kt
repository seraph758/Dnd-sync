package de.rhaeus.dndsync

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color

// 🎯 定義 MainFragment 中需要的所有顏色符號
data class ThemeColors(
    val background: Color,
    val surfaceCard: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val btnText: Color
)

object ThemeUtils {
    fun getColors(context: Context, isDark: Boolean): ThemeColors {
        val prefs = context.getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
        
        // 默認顏色
        val defaultAccent = if (isDark) 0xFFFFB74D.toInt() else 0xFFF57C00.toInt()
        
        // 從用戶設置中動態讀取顏色（如果用戶在界面上自定義了顏色的話）
        val userAccentColor = prefs.getInt("user_accent_color", defaultAccent)

        return if (isDark) {
            ThemeColors(
                background = Color(0xFF121212),
                surfaceCard = Color(0xFF1E1E1E),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFFAAAAAA),
                accent = Color(userAccentColor), // 動態自定義強調色
                btnText = Color(0xFF121212)
            )
        } else {
            ThemeColors(
                background = Color(0xFFF5F5F5),
                surfaceCard = Color(0xFFFFFFFF),
                textPrimary = Color(0xFF212121),
                textSecondary = Color(0xFF757575),
                accent = Color(userAccentColor), // 動態自定義強調色
                btnText = Color(0xFFFFFFFF)
            )
        }
    }
}
