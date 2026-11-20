package com.kotlistine.midair.core.designsystem.effects

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import com.kotlistine.midair.core.designsystem.theme.AirColors

/**
 * The palette loops around the edge like the sweep it replaces, but slow waves running along the edge bend the
 * colour bands and brighter stretches drift the other way, so the light seems to flow rather than spin. Every term
 * repeats over one turn of `phase`, so the loop has no seam.
 */
private const val GlowShader = """
uniform float2 size;
uniform float phase;
layout(color) uniform float4 c0;
layout(color) uniform float4 c1;
layout(color) uniform float4 c2;
layout(color) uniform float4 c3;

float4 palette(float t) {
    float s = fract(t) * 4.0;
    if (s < 1.0) return mix(c0, c1, s);
    if (s < 2.0) return mix(c1, c2, s - 1.0);
    if (s < 3.0) return mix(c2, c3, s - 2.0);
    return mix(c3, c0, s - 3.0);
}

half4 main(float2 p) {
    float2 d = p - size * 0.5;
    float a = atan(d.y, d.x) / 6.2831853 + 0.5;
    float turn = phase * 6.2831853;
    float bend = 0.06 * sin(a * 18.849556 + turn * 3.0) + 0.035 * sin(a * 43.982297 - turn * 5.0);
    float pulse = 0.7 + 0.3 * sin(a * 31.415927 - turn * 4.0);
    return half4(palette(a + phase + bend) * pulse);
}
"""

internal actual fun flowingGlowBrush(): FlowingGlowBrush? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // A shader that doesn't compile falls back to the plain turning sweep (and says so in the log).
        runCatching { AgslGlowBrush() }
            .onFailure { Log.w("FlowingGlow", "Edge glow shader unavailable, using the sweep", it) }
            .getOrNull()
    } else {
        null
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslGlowBrush : FlowingGlowBrush {
    private val shader = RuntimeShader(GlowShader).apply {
        // The last sweep colour repeats the first; the shader loops the palette itself.
        AirColors.GlowSweep.dropLast(1).forEachIndexed { index, color -> setColorUniform("c$index", color.toArgb()) }
    }
    private val brush = ShaderBrush(shader)

    override fun at(size: Size, phase: Float): Brush {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("phase", phase)
        return brush
    }
}
