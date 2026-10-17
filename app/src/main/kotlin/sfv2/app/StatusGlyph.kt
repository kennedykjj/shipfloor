package sfv2.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sfv2.core.Column as BoardColumn

private const val SPIN_MILLIS = 1100
private const val ARC_SWEEP = 100f

/** What a card is doing, drawn as a shape so the state never depends on color alone. */
enum class CardGlyph { QUEUED, RUNNING, WAITING, DONE, BLOCKED }

/** The glyph of a card in [column]: work columns run, the owner's columns wait, Done is done. */
fun glyphOf(column: BoardColumn, blocked: Boolean): CardGlyph =
    when {
        blocked -> CardGlyph.BLOCKED
        isWaitingForOwner(column) -> CardGlyph.WAITING
        column == BoardColumn.DONE || column == BoardColumn.READY_FOR_PROD -> CardGlyph.DONE
        column == BoardColumn.REFINEMENT || column == BoardColumn.CODING || column == BoardColumn.VALIDATION -> CardGlyph.RUNNING
        else -> CardGlyph.QUEUED
    }

/** Dashed ring, spinning arc, filled alert, filled check or filled cross; the spinner is the only motion. */
@Composable
fun StatusGlyph(glyph: CardGlyph, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val angle = if (glyph == CardGlyph.RUNNING && !reducedMotionEnabled) {
        val transition = rememberInfiniteTransition(label = "glyph-spin")
        val turning by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
            label = "glyph-angle",
        )
        turning
    } else {
        0f
    }
    val ring = Tokens.faint
    val strong = Tokens.text
    val accent = Tokens.accent
    val accentInk = Tokens.accentInk
    val ok = Tokens.ok
    val bad = Tokens.bad
    val onFill = Tokens.ground
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() * 0.12f
        val inset = stroke / 2f
        val box = Size(this.size.width - stroke, this.size.height - stroke)
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        when (glyph) {
            CardGlyph.QUEUED -> drawCircle(
                ring,
                radius = box.width / 2f,
                center = center,
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 1.4f, stroke * 1.4f))),
            )
            CardGlyph.RUNNING -> rotate(angle, center) {
                drawCircle(ring.copy(alpha = 0.35f), radius = box.width / 2f, center = center, style = Stroke(stroke))
                drawArc(strong, 0f, ARC_SWEEP, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            CardGlyph.WAITING -> {
                drawCircle(accent, radius = this.size.width / 2f - 0.5f, center = center)
                val unit = this.size.width / 14f
                drawLine(accentInk, Offset(center.x, 4f * unit), Offset(center.x, 8.2f * unit), stroke * 1.2f, StrokeCap.Round)
                drawCircle(accentInk, radius = stroke * 0.75f, center = Offset(center.x, 10.6f * unit))
            }
            CardGlyph.DONE -> {
                drawCircle(ok, radius = this.size.width / 2f - 0.5f, center = center)
                val unit = this.size.width / 14f
                val tick = Path().apply {
                    moveTo(4f * unit, 7.2f * unit)
                    lineTo(6.2f * unit, 9.4f * unit)
                    lineTo(10f * unit, 4.9f * unit)
                }
                drawPath(tick, onFill, style = Stroke(stroke * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            CardGlyph.BLOCKED -> {
                drawCircle(bad, radius = this.size.width / 2f - 0.5f, center = center)
                val unit = this.size.width / 14f
                drawLine(onFill, Offset(4.6f * unit, 4.6f * unit), Offset(9.4f * unit, 9.4f * unit), stroke * 1.2f, StrokeCap.Round)
                drawLine(onFill, Offset(9.4f * unit, 4.6f * unit), Offset(4.6f * unit, 9.4f * unit), stroke * 1.2f, StrokeCap.Round)
            }
        }
    }
}

/** True when the owner asked the desktop for less motion; motion then stays still. */
val reducedMotionEnabled: Boolean = System.getenv("SFV2_REDUCED_MOTION")?.isNotBlank() == true
