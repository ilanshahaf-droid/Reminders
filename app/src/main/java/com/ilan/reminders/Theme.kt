package com.ilan.reminders

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

/** User-chosen look: accent colour, font size and light/dark mode (settings → מראה). */
object ThemePrefs {
    var accent by mutableStateOf("indigo")
    var scale by mutableFloatStateOf(1f)
    var mode by mutableStateOf("system")   // system / light / dark
    private var loaded = false
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences("app", Context.MODE_PRIVATE)
        accent = p.getString("accent", "indigo") ?: "indigo"
        scale = p.getFloat("fontScale", 1f)
        mode = p.getString("themeMode", "system") ?: "system"
    }
    fun save(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
        .putString("accent", accent).putFloat("fontScale", scale).putString("themeMode", mode).apply()
}

/** accent -> label, light primary, light container, dark primary, dark container */
val ACCENTS: Map<String, List<Any>> = linkedMapOf(
    "indigo" to listOf("סגול-כחול", 0xFF4F46E5, 0xFFE0E7FF, 0xFF9EA0FF, 0xFF2A2C6B),
    "teal" to listOf("טורקיז", 0xFF00796B, 0xFFCCF2EC, 0xFF4DD0C0, 0xFF004D45),
    "green" to listOf("ירוק", 0xFF2E7D32, 0xFFD8F0D9, 0xFF7FD584, 0xFF1B4D1F),
    "rose" to listOf("ורוד", 0xFFC2185B, 0xFFFBDCE8, 0xFFFF8AB4, 0xFF6B1036),
    "orange" to listOf("כתום", 0xFFE65100, 0xFFFFE3D0, 0xFFFFA866, 0xFF6B2A00),
    "blue" to listOf("כחול", 0xFF1565C0, 0xFFD6E6FA, 0xFF7DB5F5, 0xFF0F3A6B)
)

@Composable
fun appDark(): Boolean = when (ThemePrefs.mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }

@Composable
fun appBackground(): Brush =
    if (appDark()) Brush.verticalGradient(listOf(Color(0xFF0E1015), Color(0xFF15142A)))
    else Brush.verticalGradient(listOf(Color(0xFFF8F8FD), Color(0xFFEBE9FA)))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    ThemePrefs.load(LocalContext.current)
    val dark = appDark()
    val a = ACCENTS[ThemePrefs.accent] ?: ACCENTS.getValue("indigo")
    val base = if (dark) Dark else Light
    val scheme = if (ThemePrefs.accent == "indigo") base else if (dark) base.copy(
        primary = Color(a[3] as Long), onPrimary = Color(0xFF101010), primaryContainer = Color(a[4] as Long), onPrimaryContainer = Color(0xFFF2F2F2)
    ) else base.copy(
        primary = Color(a[1] as Long), onPrimary = Color.White, primaryContainer = Color(a[2] as Long), onPrimaryContainer = Color(0xFF14161F)
    )
    val d = LocalDensity.current
    MaterialTheme(colorScheme = scheme, typography = Type) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalDensity provides Density(d.density, d.fontScale * ThemePrefs.scale),
            content = content
        )
    }
}
