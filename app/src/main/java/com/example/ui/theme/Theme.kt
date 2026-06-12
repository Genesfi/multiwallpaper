package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ProfessionalPrimaryDark,
    secondary = ProfessionalSecondaryDark,
    tertiary = ProfessionalTertiaryDark,
    background = ProfessionalBackgroundDark,
    surface = ProfessionalSurfaceDark,
    onPrimary = ProfessionalOnPrimaryDark,
    onSecondary = ProfessionalOnSecondaryDark,
    onBackground = ProfessionalOnBackgroundDark,
    onSurface = ProfessionalOnSurfaceDark,
    surfaceVariant = ProfessionalSurfaceVariantDark,
    onSurfaceVariant = ProfessionalOnSurfaceVariantDark,
    outline = ProfessionalOutlineDark,
    outlineVariant = ProfessionalOutlineVariantDark
  )

private val LightColorScheme =
  lightColorScheme(
    primary = ProfessionalPrimary,
    secondary = ProfessionalSecondary,
    tertiary = ProfessionalTertiary,
    background = ProfessionalBackground,
    surface = ProfessionalSurface,
    onPrimary = ProfessionalOnPrimary,
    onSecondary = ProfessionalOnSecondary,
    onBackground = ProfessionalOnBackground,
    onSurface = ProfessionalOnSurface,
    surfaceVariant = ProfessionalSurfaceVariant,
    onSurfaceVariant = ProfessionalOnSurfaceVariant,
    outline = ProfessionalOutline,
    outlineVariant = ProfessionalOutlineVariant
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Enabling dynamic colors if user wants, but default follows system dark theme
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
