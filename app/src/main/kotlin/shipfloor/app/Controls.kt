package shipfloor.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val CONTROL_HEIGHT = 28.dp
private val CONTROL_SHAPE = RoundedCornerShape(6.dp)
private val BUTTON_HEIGHT = 32.dp
private const val STROKE = 1.5f
private const val GEAR_TEETH = 8

/** The icons of the controls, drawn as strokes on a 16 by 16 grid so they follow the theme. */
enum class AppIcon {
    REFRESH, CLOSE, SUN, MOON, MONITOR, BOARD, FEED, GEAR, SEARCH, ARROW_LEFT, CHEVRON_LEFT, CHEVRON_RIGHT,
    CHECK, UNDO, SEND, PLUS, MINUS, CARDS, ROWS, FIT, CALENDAR, DOC, CLOCK, PLAY, SUMMARY, ARROW_DOWN,
}

@Composable
fun IconGlyph(icon: AppIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) { drawIcon(icon, color) }
}

private fun DrawScope.polyline(color: Color, unit: Float, vararg points: Pair<Float, Float>, close: Boolean = false) {
    val path = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit) }
        if (close) close()
    }
    drawPath(path, color, style = Stroke(STROKE * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawIcon(icon: AppIcon, color: Color) {
    val unit = size.width / 16f
    val line = Stroke(STROKE * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icon) {
        AppIcon.REFRESH -> {
            drawArc(color, 40f, 270f, false, Offset(2.5f * unit, 2.5f * unit), Size(11f * unit, 11f * unit), style = line)
            polyline(color, unit, 10.2f to 1.8f, 13.4f to 2.6f, 12.2f to 5.6f)
        }
        AppIcon.CLOSE -> {
            polyline(color, unit, 4.2f to 4.2f, 11.8f to 11.8f)
            polyline(color, unit, 11.8f to 4.2f, 4.2f to 11.8f)
        }
        AppIcon.SUN -> {
            drawCircle(color, radius = 3f * unit, center = center, style = line)
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                drawLine(
                    color,
                    Offset(center.x + (5.2f * unit * cos(angle)).toFloat(), center.y + (5.2f * unit * sin(angle)).toFloat()),
                    Offset(center.x + (7f * unit * cos(angle)).toFloat(), center.y + (7f * unit * sin(angle)).toFloat()),
                    STROKE * unit,
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
            polyline(color, unit, 5.5f to 13.4f, 10.5f to 13.4f)
        }
        AppIcon.BOARD -> listOf(2f to 10f, 6.5f to 7f, 11f to 10f).forEach { (x, h) ->
            drawRoundRect(color, Offset(x * unit, 3f * unit), Size(3f * unit, h * unit), CornerRadius(0.8f * unit), style = line)
        }
        AppIcon.FEED -> listOf(4f, 8f, 12f).forEach { y ->
            drawCircle(color, radius = 0.7f * unit, center = Offset(3.5f * unit, y * unit))
            polyline(color, unit, 6.5f to y, 13f to y)
        }
        AppIcon.GEAR -> {
            val teeth = Path()
            val steps = GEAR_TEETH * 4
            for (i in 0 until steps) {
                val angle = Math.toRadians(i * 360.0 / steps - 90.0)
                val radius = if ((i % 4) < 2) 6.6f else 5.0f
                val x = center.x + (radius * unit * cos(angle)).toFloat()
                val y = center.y + (radius * unit * sin(angle)).toFloat()
                if (i == 0) teeth.moveTo(x, y) else teeth.lineTo(x, y)
            }
            teeth.close()
            drawPath(teeth, color, style = line)
            drawCircle(color, radius = 2f * unit, center = center, style = line)
        }
        AppIcon.SEARCH -> {
            drawCircle(color, radius = 4.6f * unit, center = Offset(7f * unit, 7f * unit), style = line)
            polyline(color, unit, 10.4f to 10.4f, 13.6f to 13.6f)
        }
        AppIcon.ARROW_LEFT -> {
            polyline(color, unit, 13f to 8f, 3f to 8f)
            polyline(color, unit, 7.5f to 3.5f, 3f to 8f, 7.5f to 12.5f)
        }
        AppIcon.ARROW_DOWN -> {
            polyline(color, unit, 8f to 3f, 8f to 13f)
            polyline(color, unit, 3.5f to 8.5f, 8f to 13f, 12.5f to 8.5f)
        }
        AppIcon.CHEVRON_LEFT -> polyline(color, unit, 10f to 3.5f, 5.5f to 8f, 10f to 12.5f)
        AppIcon.CHEVRON_RIGHT -> polyline(color, unit, 6f to 3.5f, 10.5f to 8f, 6f to 12.5f)
        AppIcon.CHECK -> polyline(color, unit, 3.2f to 8.4f, 6.6f to 11.8f, 12.8f to 4.6f)
        AppIcon.UNDO -> {
            polyline(color, unit, 6f to 3.2f, 2.8f to 6.4f, 6f to 9.6f)
            val tail = Path().apply {
                moveTo(2.8f * unit, 6.4f * unit)
                lineTo(9f * unit, 6.4f * unit)
                quadraticTo(13.2f * unit, 6.4f * unit, 13.2f * unit, 10.4f * unit)
                lineTo(13.2f * unit, 12.6f * unit)
            }
            drawPath(tail, color, style = line)
        }
        AppIcon.SEND -> {
            polyline(color, unit, 2.4f to 2.6f, 13.6f to 8f, 2.4f to 13.4f, 4.8f to 8f, close = true)
            polyline(color, unit, 4.8f to 8f, 13.6f to 8f)
        }
        AppIcon.PLUS -> {
            polyline(color, unit, 8f to 3f, 8f to 13f)
            polyline(color, unit, 3f to 8f, 13f to 8f)
        }
        AppIcon.MINUS -> polyline(color, unit, 3f to 8f, 13f to 8f)
        AppIcon.CARDS -> {
            drawRoundRect(color, Offset(2.5f * unit, 2.5f * unit), Size(11f * unit, 5f * unit), CornerRadius(1.2f * unit), style = line)
            drawRoundRect(color, Offset(2.5f * unit, 9.2f * unit), Size(11f * unit, 4.3f * unit), CornerRadius(1.2f * unit), style = line)
        }
        AppIcon.ROWS -> listOf(3.2f, 6.9f, 10.6f).forEach { y ->
            drawRoundRect(color, Offset(2.5f * unit, y * unit), Size(11f * unit, 2.3f * unit), CornerRadius(1f * unit), style = line)
        }
        AppIcon.FIT -> {
            polyline(color, unit, 2.5f to 6f, 2.5f to 2.5f, 6f to 2.5f)
            polyline(color, unit, 10f to 2.5f, 13.5f to 2.5f, 13.5f to 6f)
            polyline(color, unit, 13.5f to 10f, 13.5f to 13.5f, 10f to 13.5f)
            polyline(color, unit, 6f to 13.5f, 2.5f to 13.5f, 2.5f to 10f)
        }
        AppIcon.CALENDAR -> {
            drawRoundRect(color, Offset(2.5f * unit, 3.5f * unit), Size(11f * unit, 10f * unit), CornerRadius(1.6f * unit), style = line)
            polyline(color, unit, 2.5f to 6.8f, 13.5f to 6.8f)
            polyline(color, unit, 5.5f to 2f, 5.5f to 4.6f)
            polyline(color, unit, 10.5f to 2f, 10.5f to 4.6f)
        }
        AppIcon.DOC -> {
            drawRoundRect(color, Offset(3.5f * unit, 2f * unit), Size(9f * unit, 12f * unit), CornerRadius(1.4f * unit), style = line)
            polyline(color, unit, 5.8f to 6f, 10.2f to 6f)
            polyline(color, unit, 5.8f to 8.6f, 10.2f to 8.6f)
            polyline(color, unit, 5.8f to 11.2f, 8.4f to 11.2f)
        }
        AppIcon.CLOCK -> {
            drawCircle(color, radius = 5.6f * unit, center = center, style = line)
            polyline(color, unit, 8f to 4.8f, 8f to 8f, 10.2f to 9.6f)
        }
        AppIcon.PLAY -> polyline(color, unit, 5f to 3.4f, 12.2f to 8f, 5f to 12.6f, close = true)
        AppIcon.SUMMARY -> {
            drawRoundRect(color, Offset(2.5f * unit, 3f * unit), Size(11f * unit, 10f * unit), CornerRadius(1.6f * unit), style = line)
            polyline(color, unit, 2.5f to 6.6f, 13.5f to 6.6f)
            polyline(color, unit, 6.6f to 6.6f, 6.6f to 13f)
        }
    }
}

/** Shows [text] near the pointer after a short pause; the name of an icon-only control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            BasicText(
                text,
                modifier = Modifier
                    .background(Tokens.raise, RoundedCornerShape(6.dp))
                    .border(1.dp, Tokens.edgeHi, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tokens.text),
            )
        },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}

/**
 * A square 28 dp button with an icon; [description] is its accessible name and its tooltip. [selected] marks a toggle
 * that is on.
 */
@Composable
fun IconButton(
    icon: AppIcon,
    description: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Tip(description) {
        Box(
            modifier = Modifier
                .size(CONTROL_HEIGHT)
                .alpha(if (enabled) 1f else 0.5f)
                .background(if (selected || (hovered && enabled)) Tokens.raise else Color.Transparent, CONTROL_SHAPE)
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) Tokens.accent else if (selected) Tokens.edgeHi else Tokens.edge,
                    CONTROL_SHAPE,
                )
                .hoverable(source)
                .onFocusChanged { focused = it.isFocused }
                .clickable(enabled = enabled, role = if (selected) Role.Switch else Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            IconGlyph(icon, if (selected || hovered) Tokens.text else Tokens.soft)
        }
    }
}

/** How an [AppButton] looks: the owner's answer, a main action, a secondary one. */
enum class ButtonKind { ACCENT, PRIMARY, SECONDARY }

/** A 32 dp button: an icon that says what it does, then the word. */
@Composable
fun AppButton(label: String, icon: AppIcon? = null, kind: ButtonKind = ButtonKind.SECONDARY, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val fill = when (kind) {
        ButtonKind.ACCENT -> Tokens.accent
        ButtonKind.PRIMARY -> Tokens.text
        ButtonKind.SECONDARY -> if (hovered && enabled) Tokens.raise else Color.Transparent
    }
    val ink = when (kind) {
        ButtonKind.ACCENT -> Tokens.accentInk
        ButtonKind.PRIMARY -> Tokens.ground
        ButtonKind.SECONDARY -> Tokens.text
    }
    val ring = if (kind == ButtonKind.SECONDARY) Tokens.accent else Tokens.text
    Row(
        modifier = Modifier
            .height(BUTTON_HEIGHT)
            .alpha(if (enabled) 1f else 0.45f)
            .background(fill, shape)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) ring else if (kind == ButtonKind.SECONDARY) Tokens.edgeHi else fill,
                shape,
            )
            .hoverable(source)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) IconGlyph(icon, ink)
        BasicText(label, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink))
    }
}

/** One choice out of a few, in a single capsule of icons; the chosen segment is filled and every one has a tooltip. */
@Composable
fun IconSegmented(options: List<Pair<AppIcon, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .height(CONTROL_HEIGHT)
            .border(1.dp, Tokens.edge, CONTROL_SHAPE)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, (icon, description) ->
            val on = index == selected
            var focused by remember { mutableStateOf(false) }
            Tip(description) {
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 24.dp)
                        .background(if (on) Tokens.edge else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(if (focused) 2.dp else 0.dp, if (focused) Tokens.accent else Color.Transparent, RoundedCornerShape(4.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(index) }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    IconGlyph(icon, if (on) Tokens.text else Tokens.muted)
                }
            }
        }
    }
}

/** System, light or dark: three icon segments. */
@Composable
fun ThemeSwitch(choice: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    val choices = listOf(
        Triple(ThemeChoice.SYSTEM, AppIcon.MONITOR, Texts.THEME_SYSTEM),
        Triple(ThemeChoice.LIGHT, AppIcon.SUN, Texts.THEME_LIGHT),
        Triple(ThemeChoice.DARK, AppIcon.MOON, Texts.THEME_DARK),
    )
    IconSegmented(choices.map { it.second to it.third }, choices.indexOfFirst { it.first == choice }) { onChange(choices[it].first) }
}
