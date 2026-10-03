package com.opus.airvia

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Airvia's fallback palette (used when Material You dynamic color is
// unavailable): deep blue-black with a sonic teal accent.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0E3B34),
    onPrimaryContainer = Color(0xFF9FF5E3),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF0B2B4D),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF11161C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B232C),
    onSurfaceVariant = Color(0xFF9FB0BE),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3D0500),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEF3),
    onSurfaceVariant = Color(0xFF52606D),
)

@Composable
fun AirviaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
