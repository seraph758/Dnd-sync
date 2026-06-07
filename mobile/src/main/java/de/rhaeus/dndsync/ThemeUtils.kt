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
    // 檢查目前是否為暗色模式
    fun isDarkTheme(context: Context): Boolean {
        val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    // 🎯 確保 getColors 方法精準返回 ThemeColors
    fun getColors(isDark: Boolean): ThemeColors {
        return if (isDark) {
            ThemeColors(
                background = Color(0xFF121212),
                surfaceCard = Color(0xFF1E1E1E),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFFAAAAAA),
                accent = Color(0xFFFFB74D), // 舒適的亮橘色
                btnText = Color(0xFF121212)
            )
        } else {
            ThemeColors(
                background = Color(0xFFF5F5F5),
                surfaceCard = Color(0xFFFFFFFF),
                textPrimary = Color(0xFF212121),
                textSecondary = Color(0xFF757575),
                accent = Color(0xFFF57C00), // 深橘色
                btnText = Color(0xFFFFFFFF)
            )
        }
    }
}
