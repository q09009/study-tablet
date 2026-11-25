package com.q09009.studytablet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String) { SYSTEM("시스템"), LIGHT("라이트"), DARK("다크") }

private val Light = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E5FF),
    onPrimaryContainer = Color(0xFF151C6E),
    secondary = Color(0xFF5B6275),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E8F4),
    onSecondaryContainer = Color(0xFF1B2133),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1B1E25),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1E25),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF5C6370),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFF0F2F5),
    surfaceContainerHigh = Color(0xFFEAECF0),
    surfaceContainerHighest = Color(0xFFE3E6EB),
    outline = Color(0xFFB4BAC5),
    outlineVariant = Color(0xFFE0E3E9),
    error = Color(0xFFD03B3B),
    errorContainer = Color(0xFFFDE7E7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9AA4FF),
    onPrimary = Color(0xFF111A75),
    primaryContainer = Color(0xFF353F9F),
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFB9BFD2),
    onSecondary = Color(0xFF242A3B),
    secondaryContainer = Color(0xFF2D3346),
    onSecondaryContainer = Color(0xFFDCE1F4),
    tertiary = Color(0xFF4DD0C1),
    background = Color(0xFF15171C),
    onBackground = Color(0xFFE3E5EA),
    surface = Color(0xFF1C1F25),
    onSurface = Color(0xFFE3E5EA),
    surfaceVariant = Color(0xFF2A2E37),
    onSurfaceVariant = Color(0xFFA2A8B4),
    surfaceContainerLowest = Color(0xFF111317),
    surfaceContainerLow = Color(0xFF17191E),
    surfaceContainer = Color(0xFF202329),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF30343C),
    outline = Color(0xFF4C525E),
    outlineVariant = Color(0xFF2E323A),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5C1D1D),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun StudyTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) Dark else Light, shapes = AppShapes, content = content)
}