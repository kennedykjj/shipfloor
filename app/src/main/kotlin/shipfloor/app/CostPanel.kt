package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TABULAR = "tnum"

@Composable
fun CostPanel(cost: CostSummaryView, compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.panel, RoundedCornerShape(16.dp))
            .border(1.dp, Tokens.edge, RoundedCornerShape(16.dp))
            .padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(Texts.COST_TITLE, style = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
            Tag(Texts.ESTIMATE_TAG, Tokens.muted)
        }
        AmountLine(cost.total, big = true)
        cost.total.tag?.let { Tag(tagText(it), Tokens.warn) }
        if (cost.explainsPartial) {
            BasicText(Texts.PARTIAL_EXPLANATION, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
        }
        Section(Texts.COST_BY_CARD, cost.cards)
        Section(Texts.COST_BY_STAGE, cost.stages)
        Section(Texts.COST_BY_DAY, cost.days)
    }
}

@Composable
private fun Section(title: String, lines: List<CostLine>) {
    if (lines.isEmpty()) return
    BasicText(title, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tokens.soft))
    lines.forEach { AmountLine(it, big = false) }
}

@Composable
private fun AmountLine(line: CostLine, big: Boolean) {
    val reached = line.limitState == LimitState.Reached
    val color = if (reached) Tokens.bad else Tokens.text
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!big) {
                BasicText(
                    line.label,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft),
                )
            }
            if (reached) {
                Box(Modifier.size(8.dp).background(Tokens.bad).semantics { contentDescription = Texts.LIMIT_REACHED })
            }
            line.tag?.takeUnless { big }?.let { Tag(tagText(it), Tokens.warn) }
            BasicText(
                valueText(line),
                style = TextStyle(
                    fontFamily = sans,
                    fontSize = if (big) 24.sp else 14.sp,
                    fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal,
                    fontFeatureSettings = TABULAR,
                    color = color,
                ),
            )
        }
        line.fraction?.let { Bar(it, color) }
        if (reached) {
            val label = if ((line.fraction ?: 0.0) > 1.0) Texts.LIMIT_EXCEEDED else Texts.LIMIT_REACHED
            BasicText(label, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.bad))
        }
    }
}

private fun valueText(line: CostLine): String = line.limit?.let { "${line.value} ${Texts.LIMIT_OF} $it" } ?: line.value

@Composable
private fun Bar(fraction: Double, fill: Color) {
    val shown = fraction.coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tokens.edge)) {
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

@Composable
private fun Tag(text: String, color: Color) {
    BasicText(
        text,
        modifier = Modifier
            .border(1.dp, Tokens.edge, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = text },
        style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = color),
    )
}

private fun tagText(tag: CostTag): String =
    when (tag) {
        CostTag.Partial -> Texts.PARTIAL
        CostTag.Unknown -> Texts.UNKNOWN
    }
