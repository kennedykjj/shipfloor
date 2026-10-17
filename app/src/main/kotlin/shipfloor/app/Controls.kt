package shipfloor.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CONTROL_HEIGHT = 28.dp
private val CONTROL_SHAPE = RoundedCornerShape(6.dp)
private val ICON_SIZE = 16.dp

/** The icons of the controls, drawn as strokes so they follow the theme. */
enum class AppIcon { REFRESH, SUN, MOON, MONITOR }

@Composable
fun IconGlyph(icon: AppIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(ICON_SIZE)) { drawIcon(icon, color) }
}

private fun DrawScope.drawIcon(icon: AppIcon, color: Color) {
    val unit = size.width / 16f
    val line = Stroke(1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icon) {
        AppIcon.REFRESH -> {
            drawArc(color, 40f, 270f, false, Offset(2.5f * unit, 2.5f * unit), Size(11f * unit, 11f * unit), style = line)
            val head = Path().apply {
                moveTo(10.2f * unit, 1.8f * unit)
                lineTo(13.4f * unit, 2.6f * unit)
                lineTo(12.2f * unit, 5.6f * unit)
            }
            drawPath(head, color, style = line)
        }
        AppIcon.SUN -> {
            drawCircle(color, radius = 3f * unit, center = center, style = line)
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val inner = 5.2f * unit
                val outer = 7f * unit
                drawLine(
                    color,
                    Offset(center.x + (inner * Math.cos(angle)).toFloat(), center.y + (inner * Math.sin(angle)).toFloat()),
                    Offset(center.x + (outer * Math.cos(angle)).toFloat(), center.y + (outer * Math.sin(angle)).toFloat()),
                    1.5f * unit,
                    StrokeCap.Round,
                )
            }
        }
        AppIcon.MOON -> {
            val moon = Path().apply {
                moveTo(12.8f * unit, 9.6f * unit)
                cubicTo(11.6f * unit, 10.4f * unit, 10.2f * unit, 10.8f * unit, 8.8f * unit, 10.4f * unit)
                cubicTo(6.4f * unit, 9.8f * unit, 5.2f * unit, 7.2f * unit, 6f * unit, 5f * unit)
                cubicTo(6.3f * unit, 4.2f * unit, 6.8f * unit, 3.6f * unit, 7.4f * unit, 3.2f * unit)
                cubicTo(4.2f * unit, 3.4f * unit, 2f * unit, 6f * unit, 2.6f * unit, 9f * unit)
                cubicTo(3.2f * unit, 11.8f * unit, 6f * unit, 13.6f * unit, 8.8f * unit, 13f * unit)
                cubicTo(10.6f * unit, 12.6f * unit, 12.2f * unit, 11.4f * unit, 12.8f * unit, 9.6f * unit)
                close()
            }
            drawPath(moon, color, style = line)
        }
        AppIcon.MONITOR -> {
            drawRoundRect(color, Offset(1.8f * unit, 2.8f * unit), Size(12.4f * unit, 8f * unit), CornerRadius(1.4f * unit), style = line)
            drawLine(color, Offset(5.5f * unit, 13.4f * unit), Offset(10.5f * unit, 13.4f * unit), 1.5f * unit, StrokeCap.Round)
        }
    }
}

/** A square 28 dp button with an icon; [description] is its accessible name. */
@Composable
fun IconButton(icon: AppIcon, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(CONTROL_HEIGHT)
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (hovered && enabled) Tokens.raise else Color.Transparent, CONTROL_SHAPE)
            .border(if (focused) 2.dp else 1.dp, if (focused) Tokens.accent else Tokens.edge, CONTROL_SHAPE)
            .hoverable(source)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        IconGlyph(icon, if (hovered) Tokens.text else Tokens.soft)
    }
}

/** A labeled on/off switch: the track is filled when on. */
@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .height(CONTROL_HEIGHT)
            .border(if (focused) 2.dp else 0.dp, if (focused) Tokens.accent else Color.Transparent, CONTROL_SHAPE)
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(label, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Tokens.soft))
        Box(
            Modifier
                .size(width = 30.dp, height = 18.dp)
                .background(if (checked) Tokens.text else Tokens.edgeHi, RoundedCornerShape(9.dp))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(14.dp).background(if (checked) Tokens.ground else Tokens.panel, RoundedCornerShape(7.dp)))
        }
    }
}

/** One choice out of a few, in a single capsule: the chosen segment is filled. Each label is also its accessible name. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .height(CONTROL_HEIGHT)
            .border(1.dp, Tokens.edge, CONTROL_SHAPE)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .background(if (on) Tokens.edge else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(if (focused) 2.dp else 0.dp, if (focused) Tokens.accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) Tokens.text else Tokens.muted),
                )
            }
        }
    }
}

/** System, light or dark: three icon segments. */
@Composable
fun ThemeSwitch(choice: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    Row(
        modifier = Modifier
            .height(CONTROL_HEIGHT)
            .border(1.dp, Tokens.edge, CONTROL_SHAPE)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            Triple(ThemeChoice.SYSTEM, AppIcon.MONITOR, Texts.THEME_SYSTEM),
            Triple(ThemeChoice.LIGHT, AppIcon.SUN, Texts.THEME_LIGHT),
            Triple(ThemeChoice.DARK, AppIcon.MOON, Texts.THEME_DARK),
        ).forEach { (value, icon, description) ->
            val on = value == choice
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 24.dp)
                    .background(if (on) Tokens.edge else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(if (focused) 2.dp else 0.dp, if (focused) Tokens.accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .selectable(selected = on, role = Role.RadioButton) { onChange(value) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                IconGlyph(icon, if (on) Tokens.text else Tokens.muted)
            }
        }
    }
}

