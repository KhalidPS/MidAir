package com.kotlistine.midair.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlistine.midair.core.designsystem.motion.LocalReducedMotion
import com.kotlistine.midair.core.designsystem.motion.systemReducedMotion

private val AirColorScheme = darkColorScheme(
    primary = AirColors.OnSurface,
    onPrimary = AirColors.Background,
    secondary = AirColors.GlowPink,
    tertiary = AirColors.GlowOrange,
    background = AirColors.Background,
    onBackground = AirColors.OnSurface,
    surface = AirColors.Surface,
    onSurface = AirColors.OnSurface,
    surfaceVariant = AirColors.SurfaceRaised,
    onSurfaceVariant = AirColors.OnSurfaceMuted,
    outline = AirColors.Outline,
)

private val AirTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    // File name under a card ("Golden Hour.jpg").
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    // "367 KB · 13:25".
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
)

private val AirShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

/** The app is dark-only, like the reference: the glow and bloom effects need a black canvas. */
@Composable
fun AirTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides systemReducedMotion()) {
        MaterialTheme(
            colorScheme = AirColorScheme,
            typography = AirTypography,
            shapes = AirShapes,
            content = content,
        )
    }
}
