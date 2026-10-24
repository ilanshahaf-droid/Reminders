package com.ilan.reminders

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFFECEEFB), onSecondaryContainer = Color(0xFF2B2E4A),
    background = Color(0xFFF5F6FA), onBackground = Color(0xFF14161F),
    surface = Color(0xFFFDFDFF), onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFECEEF5), onSurfaceVariant = Color(0xFF626880),
    outline = Color(0xFFB8BBC9), error = Color(0xFFDC2626)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9EA0FF), onPrimary = Color(0xFF14143A),
    primaryContainer = Color(0xFF2A2C6B), onPrimaryContainer = Color(0xFFE0E1FF),
    secondaryContainer = Color(0xFF262A38), onSecondaryContainer = Color(0xFFD7DAEC),
    background = Color(0xFF0E1015), onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF1A1D26), onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF232733), onSurfaceVariant = Color(0xFF9AA0B8),
    outline = Color(0xFF3A3F52), error = Color(0xFFFF6B6B)
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    bodySmall = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun appBackground(): Brush =
    if (isSystemInDarkTheme()) Brush.verticalGradient(listOf(Color(0xFF0E1015), Color(0xFF15142A)))
    else Brush.verticalGradient(listOf(Color(0xFFF8F8FD), Color(0xFFEBE9FA)))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Type
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
