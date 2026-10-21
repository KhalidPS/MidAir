package com.kotlistine.transferingfilebyair.core.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Colour tokens sampled from the reference video: near-black canvas, soft grey cards, neon edge glow. */
object AirColors {
    val Background = Color(0xFF060608)
    val Surface = Color(0xFF141418)
    val SurfaceRaised = Color(0xFF1E1E24)
    val Outline = Color(0x1FFFFFFF)
    val OnSurface = Color(0xFFF5F5F7)
    val OnSurfaceMuted = Color(0xFF8E8E96)

    // Document previews: a light sheet of paper on the dark card.
    val Paper = Color(0xFFEDEDF1)
    val PaperLine = Color(0xFFD3D3DB)
    val PaperInk = Color(0xFF3A3A44)

    // Edge glow / bloom palette (pairing ring, portal rim).
    val GlowPink = Color(0xFFFF2E93)
    val GlowMagenta = Color(0xFFB53DFF)
    val GlowOrange = Color(0xFFFF8A1F)
    val GlowBlue = Color(0xFF2F7BFF)
    val GlowCyan = Color(0xFF3FE0D0)
    val GlowYellow = Color(0xFFFFE15A)
    val BloomCore = Color(0xFFFFFFFF)

    /** Ordered so a sweep gradient loops seamlessly around a rounded screen edge. */
    val GlowSweep = listOf(GlowPink, GlowOrange, GlowMagenta, GlowBlue, GlowPink)

    /** Chromatic fringe on the rim of a bloom, inner band first (warm → cool → pink), like light through a prism. */
    val IridescentFringe = listOf(GlowYellow, GlowCyan, GlowBlue, GlowMagenta, GlowPink)

    /** Pink → orange, used for the peer device name ("Khalid's Pixel"). */
    val HeadlineGradient: Brush = Brush.linearGradient(listOf(GlowPink, GlowMagenta, GlowOrange))
}
