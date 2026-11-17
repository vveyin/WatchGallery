package com.watchgallery.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 配色 —— 借鉴 f-x 的「夜间黑底 / 白天白纸」双主题思路。
 * 夜间为默认，白天为亮色纸张风格。
 */
object AppColors {
    // 品牌主色（蓝紫，接近 f-x 的靛蓝）
    val Primary = Color(0xFF5B6EE1)
    val PrimaryDark = Color(0xFFBAC3FF)
    val Accent = Color(0xFFFFB74D)

    // 夜间配色
    val NightBackground = Color(0xFF08090C)
    val NightSurface = Color(0xFF14161C)
    val NightSurfaceVariant = Color(0xFF1E2129)
    val NightOnSurface = Color(0xFFECEFF5)
    val NightOnSurfaceMuted = Color(0xFF9AA0AE)

    // 白天配色（纸张感）
    val DayBackground = Color(0xFFF4F5F9)
    val DaySurface = Color(0xFFFFFFFF)
    val DaySurfaceVariant = Color(0xFFE8EAF2)
    val DayOnSurface = Color(0xFF1A1C22)
    val DayOnSurfaceMuted = Color(0xFF6B7080)
}

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.PrimaryDark,
    onPrimary = Color(0xFF1A237E),
    primaryContainer = Color(0xFF2A2F6B),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = AppColors.Accent,
    background = AppColors.NightBackground,
    onBackground = AppColors.NightOnSurface,
    surface = AppColors.NightSurface,
    onSurface = AppColors.NightOnSurface,
    surfaceVariant = AppColors.NightSurfaceVariant,
    onSurfaceVariant = AppColors.NightOnSurfaceMuted,
    error = Color(0xFFFF8A80),
    outline = Color(0xFF4A4F5C)
)

private val LightColorScheme = lightColorScheme(
    primary = AppColors.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF1A237E),
    secondary = Color(0xFFE65100),
    background = AppColors.DayBackground,
    onBackground = AppColors.DayOnSurface,
    surface = AppColors.DaySurface,
    onSurface = AppColors.DayOnSurface,
    surfaceVariant = AppColors.DaySurfaceVariant,
    onSurfaceVariant = AppColors.DayOnSurfaceMuted,
    error = Color(0xFFB3261E),
    outline = Color(0xFFC4C7D2)
)

/** 主题模式：跟随系统 / 强制夜间 / 强制白天（f-x 默认夜间） */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun WatchGalleryTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
