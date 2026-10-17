package sfv2.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WRAP_BELOW = 1100.dp

/**
 * The factory header: what the factory says and how many cards wait for the owner on the left; the sessions in use,
 * the day ceiling and the forecast on the right. Everything comes from [view] and is drawn as plain text; card labels
 * are `#n` or 8 hex, never titles.
 */
@Composable
fun PulseStrip(view: PulseView, modifier: Modifier = Modifier, waiting: Int = 0) {
    val dim = if (view.freshness == PulseFreshness.Stale) 0.55f else 1f
    Column(modifier = modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        view.notice?.let { notice ->
            BasicText(
                notice,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.warn),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().alpha(dim)) {
            if (maxWidth >= WRAP_BELOW) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Says(view, waiting, Modifier.weight(1f))
                    Gauges(view)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Says(view, waiting, Modifier.fillMaxWidth())
                    Gauges(view)
                }
            }
        }
    }
}

@Composable
private fun Says(view: PulseView, waiting: Int, modifier: Modifier) {
    val description = listOfNotNull(PulseTexts.TITLE, view.stateText, view.phrase).joinToString(", ")
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (view.stateText == null) {
            BasicText(PulseTexts.LOADING, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.faint))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusGlyph(if (view.activeCards.isEmpty()) CardGlyph.QUEUED else CardGlyph.RUNNING, size = 18.dp)
                BasicText(
                    view.stateText,
                    style = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text),
                )
                if (waiting > 0) {
                    BasicText(
                        Texts.waitingForYou(waiting),
                        style = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Tokens.sand),
                    )
                }
            }
            view.phrase?.let {
                BasicText(it, maxLines = 1, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.muted))
            }
        }
    }
}

@Composable
private fun Gauges(view: PulseView) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Sessions(view)
        Ceiling(view)
        view.forecast?.let { forecast ->
            Column(Modifier.semantics { contentDescription = forecast.description }, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(forecast.headline, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Tokens.soft, fontFeatureSettings = "tnum"))
                forecast.secondary?.let {
                    BasicText(it, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.faint))
                }
            }
        }
    }
}

@Composable
private fun Sessions(view: PulseView) {
    val sessions = view.sessionsText ?: return
    val description = listOfNotNull(PulseTexts.NOW, sessions, view.activeCards.takeIf { it.isNotEmpty() }?.joinToString(" ")).joinToString(", ")
    Column(Modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText(sessions, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Tokens.soft, fontFeatureSettings = "tnum"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            view.squares.forEach { used ->
                Box(Modifier.size(width = 20.dp, height = 6.dp).background(if (used) Tokens.text else Tokens.edgeHi, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun Ceiling(view: PulseView) {
    val ceiling = view.ceiling ?: return
    val description = when (ceiling) {
        is PulseCeiling.Unlimited -> "${PulseTexts.CEILING_TITLE}, ${ceiling.text}"
        is PulseCeiling.Meter -> "${PulseTexts.CEILING_TITLE}, ${PulseTexts.meter(ceiling.percent)}, ${ceiling.spent}"
    }
    Column(Modifier.width(150.dp).semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        when (ceiling) {
            is PulseCeiling.Unlimited ->
                BasicText(ceiling.text, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
            is PulseCeiling.Meter -> {
                val color = when (ceiling.limitState) {
                    LimitState.Reached -> Tokens.bad
                    LimitState.Within -> if (ceiling.percent >= WARN_PERCENT) Tokens.warn else Tokens.text
                    LimitState.NoLimit -> Tokens.muted
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(ceiling.spent, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Tokens.soft, fontFeatureSettings = "tnum"))
                    BasicText("${ceiling.percent}%", style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.faint, fontFeatureSettings = "tnum"))
                }
                Meter(ceiling.percent / 100f, color, PulseTexts.meter(ceiling.percent))
            }
        }
    }
}

private const val WARN_PERCENT = 80

@Composable
private fun Meter(fraction: Float, fill: Color, description: String) {
    val shown = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Tokens.edge)
            .semantics { contentDescription = description },
    ) {
        Box(
            Modifier
                .height(4.dp)
                .layout { measurable, constraints ->
                    val width = (constraints.maxWidth * shown).toInt()
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(width, placeable.height) { placeable.place(0, 0) }
                }
                .background(fill),
        )
    }
}
