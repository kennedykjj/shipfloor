package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Strings of the command palette (pt-BR), apart from the view labels that live in [PaletteView]. */
internal object PaletteTexts {
    const val NO_RESULT = "Nenhum resultado"
    const val FIELD = "Buscar card ou tela"
}

private val SCRIM = Color(0x99000000)
private val BOX_SHAPE = RoundedCornerShape(14.dp)

/**
 * Draws the palette over the screen. Keys other than typing are handled by the window (see `Main.kt`);
 * the card title is external text, drawn as plain text and never logged.
 */
@Composable
internal fun PaletteScreen(
    state: PaletteState,
    entries: List<PaletteEntry>,
    onQuery: (String) -> Unit,
    onChoose: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val active = entries.getOrNull(state.active)
    val fieldDescription = active?.let { "${PaletteTexts.FIELD}: ${describe(it)}" } ?: PaletteTexts.FIELD
    Box(
        Modifier.fillMaxSize().background(SCRIM)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 64.dp, start = 16.dp, end = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .clip(BOX_SHAPE).background(Tokens.panel).border(1.dp, Tokens.edgeHi, BOX_SHAPE)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            BasicTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontFamily = sans, fontSize = 15.sp, color = Tokens.text),
                cursorBrush = SolidColor(Tokens.text),
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus)
                    .semantics { contentDescription = fieldDescription },
            )
            if (entries.isEmpty()) {
                BasicText(
                    PaletteTexts.NO_RESULT,
                    modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted),
                )
            } else {
                entries.forEachIndexed { index, entry -> PaletteRow(entry, index == state.active) { onChoose(index) } }
            }
        }
    }
}

private fun describe(entry: PaletteEntry): String = when (entry) {
    is PaletteEntry.View -> entry.view.label
    is PaletteEntry.Card -> "#${entry.card.id} ${entry.card.title}, ${entry.card.column.displayName}"
}

@Composable
private fun PaletteRow(entry: PaletteEntry, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (active) Tokens.raise else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (active) Tokens.text else Color.Transparent))
        when (entry) {
            is PaletteEntry.View -> BasicText(
                entry.view.label,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
            )
            is PaletteEntry.Card -> Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "#${entry.card.id}",
                    style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = if (entry.waiting) Tokens.sand else Tokens.muted),
                )
                BasicText(
                    entry.card.title,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
                )
                BasicText(
                    entry.card.column.displayName,
                    style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.muted),
                )
            }
        }
    }
}
