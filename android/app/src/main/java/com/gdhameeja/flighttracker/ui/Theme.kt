package com.gdhameeja.flighttracker.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF1E4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0B1B3A),
    secondary = Color(0xFF4B5C8C),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF0B1B3A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFB9C6EA),
    background = Color(0xFF0A0F1C),
    surface = Color(0xFF0A0F1C),
    surfaceContainer = Color(0xFF151B2B),
)

/** Status colours: the same meaning everywhere, readable in light and dark. */
data class ToneColors(val good: Color, val warn: Color, val bad: Color, val live: Color, val neutral: Color) {
    fun of(t: Tone) = when (t) {
        Tone.Good -> good
        Tone.Warn -> warn
        Tone.Bad -> bad
        Tone.Live -> live
        Tone.Neutral -> neutral
    }
}

private val LightTones = ToneColors(Color(0xFF138A4B), Color(0xFFB45F06), Color(0xFFC62828), Color(0xFF1E4FD8), Color(0xFF5F6577))
private val DarkTones = ToneColors(Color(0xFF5BD18E), Color(0xFFFFB454), Color(0xFFFF7A70), Color(0xFF8FB0FF), Color(0xFFA3A9BA))

val LocalTones = staticCompositionLocalOf { LightTones }

private val Mono = FontFamily.Default

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Big airport codes and times. */
val CodeStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 0.5.sp)
val TimeStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 17.sp)

@Composable
fun FlightTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Follows your wallpaper colours on Android 12+
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTones provides if (dark) DarkTones else LightTones) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
