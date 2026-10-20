package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalAppLanguage = staticCompositionLocalOf { "ar" }

@Composable
fun t(ar: String, en: String): String {
    return if (LocalAppLanguage.current == "ar") ar else en
}

private val DarkColorScheme =
  darkColorScheme(
    primary = GBRBlueMain,
    secondary = GBRCyanAccent,
    tertiary = GBRPinkAccent,
    background = IndustrialDarkBg,
    surface = IndustrialDarkSurface,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = IndustrialTextLight,
    onSurface = IndustrialTextLight,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = GBRBlueMain,
    secondary = GBRCyanAccent,
    tertiary = GBRPinkAccent,
    background = IndustrialGrayBg,
    surface = IndustrialSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = IndustrialTextDark,
    onSurface = IndustrialTextDark,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
