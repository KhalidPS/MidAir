package com.kotlistine.transferingfilebyair.core.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 24×24 line glyphs drawn in black; `Icon` tints them. */
object AirIcons {

    val Add: ImageVector by lazy {
        icon("Add") {
            stroke {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            fill {
                circle(5.5f, 12f, 1.7f)
                circle(12f, 12f, 1.7f)
                circle(18.5f, 12f, 1.7f)
            }
        }
    }

    /** Two phones side by side: the app's "paired device" mark. */
    val Devices: ImageVector by lazy {
        icon("Devices") {
            stroke(width = 1.6f) {
                roundRect(2.5f, 4.5f, 9f, 15f, 2f)
                roundRect(12.5f, 4.5f, 9f, 15f, 2f)
            }
        }
    }

    val Play: ImageVector by lazy {
        icon("Play") {
            fill {
                moveTo(8.5f, 6f)
                lineTo(18.5f, 12f)
                lineTo(8.5f, 18f)
                close()
            }
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private inline fun ImageVector.Builder.stroke(width: Float = 2f, block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private inline fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.roundRect(left: Float, top: Float, width: Float, height: Float, radius: Float) {
        val right = left + width
        val bottom = top + height
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right, y1 = top + radius)
        lineTo(right, bottom - radius)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right - radius, y1 = bottom)
        lineTo(left + radius, bottom)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left, y1 = bottom - radius)
        lineTo(left, top + radius)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left + radius, y1 = top)
        close()
    }
}
