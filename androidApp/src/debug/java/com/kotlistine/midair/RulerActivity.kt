package com.kotlistine.midair

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View

private const val LineMm = 50f
private const val MmPerInch = 25.4f

/**
 * Debug-only: a 50 mm line across and one down, drawn from the densities the app works in millimetres with
 * (`displayMetrics.xdpi/ydpi`, as `ScreenDensity` gets them). Measure them with a ruler: a line that isn't 50 mm means
 * this phone reports its density wrong by that much, and its half of a card is that much off in size and speed.
 */
class RulerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(RulerView(this))
    }
}

private class RulerView(context: Context) : View(context) {

    private val metrics = context.resources.displayMetrics
    private val line = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
        isAntiAlias = false
    }
    private val label = Paint().apply {
        color = Color.WHITE
        textSize = 14f * metrics.density
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val xPx = LineMm / MmPerInch * metrics.xdpi
        val yPx = LineMm / MmPerInch * metrics.ydpi
        val left = width * 0.1f
        val top = height * 0.3f
        val tick = 4f / MmPerInch * metrics.xdpi

        // Across, with a tick every 10 mm.
        canvas.drawLine(left, top, left + xPx, top, line)
        for (mm in 0..LineMm.toInt() step 10) {
            val x = left + mm / MmPerInch * metrics.xdpi
            canvas.drawLine(x, top - tick, x, top + tick, line)
        }
        // Down, from the same corner.
        val downTop = top + tick * 4
        canvas.drawLine(left, downTop, left, downTop + yPx, line)
        for (mm in 0..LineMm.toInt() step 10) {
            val y = downTop + mm / MmPerInch * metrics.ydpi
            canvas.drawLine(left - tick, y, left + tick, y, line)
        }

        val text = listOf(
            "Both lines should be 50 mm",
            "xdpi ${"%.1f".format(metrics.xdpi)}, ydpi ${"%.1f".format(metrics.ydpi)}, densityDpi ${metrics.densityDpi}",
            "${metrics.widthPixels} x ${metrics.heightPixels} px",
        )
        text.forEachIndexed { i, s -> canvas.drawText(s, left, height * 0.1f + i * label.textSize * 1.4f, label) }
    }
}
