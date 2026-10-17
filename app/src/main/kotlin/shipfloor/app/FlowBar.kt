package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LEGEND_HIDDEN_BELOW = 880.dp
private val LABEL_MIN_WIDTH = 56.dp
private val STRIPE_STEP = 8.dp

/**
 * "Onde o tempo vai": a stacked bar of the median time per stage and the share spent waiting for the owner.
 * Built only from column names and numbers; waiting segments are hatched so the color is not the only cue.
 */
@Composable
internal fun FlowBar(split: TimeSplit, dimmed: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().alpha(if (dimmed) 0.55f else 1f)) {
        Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = Texts.flowDescription(split) },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(Texts.FLOW_TITLE, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
                BasicText(phrase(split), style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
            }
            Bar(split)
            if (this@BoxWithConstraints.maxWidth >= LEGEND_HIDDEN_BELOW) Legend(split)
        }
    }
}

private fun phrase(split: TimeSplit) =
    buildAnnotatedString {
        append(Texts.FLOW_PHRASE)
        withStyle(SpanStyle(color = Tokens.sand, fontWeight = FontWeight.SemiBold)) { append(Texts.flowWait(split.waitPercent)) }
    }

@Composable
private fun Bar(split: TimeSplit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().height(26.dp).clip(shape).border(1.dp, Tokens.edge, shape)) {
        split.segments.forEach { segment ->
            val waiting = isWaitingForOwner(segment.column)
            BoxWithConstraints(
                Modifier
                    .weight(segment.seconds.coerceAtLeast(1L).toFloat())
                    .fillMaxSize()
                    .background(hueColor(stageHue(segment.column)))
                    .then(if (waiting) Modifier.drawBehind { stripes() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (maxWidth >= LABEL_MIN_WIDTH) {
                    BasicText(
                        ColumnTimeLabel.duration(segment.seconds),
                        style = TextStyle(fontFamily = mono, fontSize = 11.sp, color = Tokens.ground),
                    )
                }
            }
        }
    }
}

private fun DrawScope.stripes() {
    val step = STRIPE_STEP.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(Tokens.ground.copy(alpha = 0.28f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2f)
        x += step
    }
}

@Composable
private fun Legend(split: TimeSplit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        split.segments.forEach { segment ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(hueColor(stageHue(segment.column))))
                BasicText(
                    "${segment.column.displayName} ${ColumnTimeLabel.duration(segment.seconds)}",
                    modifier = Modifier.padding(end = 2.dp),
                    style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.muted),
                )
            }
        }
    }
}
