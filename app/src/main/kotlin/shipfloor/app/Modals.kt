package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Clock
import shipfloor.core.Column as BoardColumn

private const val LIST_WIDTH = 0.86f
private const val LIST_HEIGHT = 0.88f
private val GRID_MIN = 260.dp
private val CHIP_GRID_MIN = 360.dp

/**
 * A modal: a scrim over the whole window and a panel in the middle, [widthShare] by [heightShare] of the window. A
 * click on the scrim calls [onClose]; a click on the panel does nothing. Esc is handled by the window.
 */
@Composable
fun ModalFrame(widthShare: Float, heightShare: Float, scrim: Float, label: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val none = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).clickable(interactionSource = none, indication = null, onClick = onClose),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(maxWidth * widthShare)
                .height(maxHeight * heightShare)
                .shadow(28.dp, shape)
                .clip(shape)
                .background(Tokens.ground)
                .border(1.dp, Tokens.edgeHi, shape)
                .clickable(interactionSource = none, indication = null) {}
                .semantics { paneTitle = label },
        ) { content() }
    }
}

/**
 * Every card of a [column], in a grid: filter by number or title, order, close. A click on a card opens its detail.
 * Titles are external text: drawn as plain text.
 */
@Composable
fun ColumnListModal(
    column: BoardColumn,
    cards: List<BoardCard>,
    density: BoardDensity,
    onOpenCard: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember(column) { mutableStateOf("") }
    var order by remember(column) { mutableStateOf(ListOrder.OLDEST) }
    val shown = cardsForList(cards, query, order)
    val chips = density == BoardDensity.CHIPS
    ModalFrame(LIST_WIDTH, LIST_HEIGHT, scrim = 0.6f, label = column.displayName, onClose = onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(column.displayName, style = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
                CountText(if (query.isBlank()) "${cards.size}" else "${shown.size} de ${cards.size}")
                Box(Modifier.weight(1f))
                TextSegmented(
                    listOf(Texts.ORDER_OLDEST, Texts.ORDER_TITLE),
                    selected = order.ordinal,
                ) { order = ListOrder.entries[it] }
                FilterField(query, onClose) { query = it }
                IconButton(AppIcon.CLOSE, Texts.CLOSE_MODAL, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.edge))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty()) {
                    BasicText(Texts.NO_MATCH, modifier = Modifier.padding(24.dp), style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.faint))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (chips) CHIP_GRID_MIN else GRID_MIN),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (chips) 20.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (chips) 0.dp else 10.dp),
                    ) {
                        items(shown, key = { it.id }) { card ->
                            if (chips) Chip(card, open = false, takesFocus = false, onOpenCard = onOpenCard) else CardTile(card, onOpenCard)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.edge))
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                BasicText(Texts.LIST_HINT, style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, color = Tokens.faint))
                Box(Modifier.weight(1f))
                BasicText(Texts.ESC_CLOSES, style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, color = Tokens.faint))
            }
        }
    }
}

/** A card in the list modal: the same face as on the board, with no drag and no buttons. */
@Composable
private fun CardTile(card: BoardCard, onOpenCard: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val face = CardFace.of(card, Clock.systemUTC())
    Box(
        Modifier
            .fillMaxWidth()
            .background(Tokens.panel, shape)
            .border(1.dp, if (isWaitingForOwner(card.column)) Tokens.sand.copy(alpha = 0.6f) else Tokens.edge, shape)
            .clickable(role = Role.Button) { onOpenCard(card.id) }
            .semantics { contentDescription = face.description }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) { CardBody(card, face, glyphOf(card.column, card.blocked)) }
}

@Composable
private fun CountText(text: String) {
    BasicText(
        text,
        modifier = Modifier.background(Tokens.raise, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
        style = TextStyle(fontFamily = mono, fontSize = 11.5.sp, color = Tokens.muted, fontFeatureSettings = "tnum"),
    )
}

/** The filter box of the list: a magnifier, the text; Esc closes the whole list, so the window does not. */
@Composable
private fun FilterField(query: String, onClose: () -> Unit, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.width(260.dp).height(30.dp).background(Tokens.panel, shape).border(1.dp, Tokens.edge, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconGlyph(AppIcon.SEARCH, Tokens.faint)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) BasicText(Texts.LIST_FILTER_HINT, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.faint))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                cursorBrush = SolidColor(Tokens.text),
                textStyle = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .claimsEscape()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

/** One choice out of a few, as words in a single capsule. */
@Composable
fun TextSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.height(28.dp).border(1.dp, Tokens.edge, RoundedCornerShape(6.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .height(24.dp)
                    .background(if (on) Tokens.edge else Color.Transparent, RoundedCornerShape(4.dp))
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (on) Tokens.text else Tokens.muted),
                )
            }
        }
    }
}
