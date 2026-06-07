package de.rhaeus.dndsync

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ThemeUtils {
    // 优雅的现代科技蓝 - 浅色主题
    private val LightColors = lightColorScheme(
        primary = Color(0xFF005AC1),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFFF8F9FC),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F),
        outline = Color(0xFF74777F)
    )

    // 优雅的硬核极客黑 - 暗色主题
    private val DarkColors = darkColorScheme(
        primary = Color(0xFFADC6FF),
        onPrimary = Color(0xFF002E69),
        surface = Color(0xFF10131A),
        onSurface = Color(0xFFE3E2E6),
        surfaceVariant = Color(0xFF44474F),
        onSurfaceVariant = Color(0xFFC4C6D0),
        outline = Color(0xFF8E9099)
    )

    @Composable
    fun getColors(): ColorScheme {
        return if (isSystemInDarkTheme()) DarkColors else LightColors
    }
}
