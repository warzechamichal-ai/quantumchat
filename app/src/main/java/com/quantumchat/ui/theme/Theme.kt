package com.quantumchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CyberTeal,
    secondary = ElectricViolet,
    tertiary = MintGreen,
    background = SlateDarkBg,
    surface = SlateSurface,
    onPrimary = SlateDarkBg,
    onSecondary = SlateDarkBg,
    onTertiary = SlateDarkBg,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = CyberTealDark,
    secondary = ElectricVioletDark,
    tertiary = MintGreenDark,
    background = SlateLightBg,
    surface = SlateLightSurface,
    onPrimary = SlateLightBg,
    onSecondary = SlateLightBg,
    onTertiary = SlateLightBg,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight
)

@Composable
fun QuantumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
