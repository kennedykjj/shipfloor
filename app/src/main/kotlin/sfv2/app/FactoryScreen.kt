package sfv2.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The three places the app bar leads to. */
enum class AppSection { BOARD, FEED }

/**
 * The app bar: brand, the places of the app and the search that opens the palette. [feedAvailable] false hides the
 * Feed (no daemon), [onSettings] null hides Settings.
 */
@Composable
fun AppBar(
    section: AppSection,
    feedAvailable: Boolean,
    onSelect: (AppSection) -> Unit,
    onSettings: (() -> Unit)?,
    onSearch: () -> Unit,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Tokens.panel)
            .drawBehind { drawLine(Tokens.edge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Logo()
            BasicText(Texts.TITLE, style = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            NavItem(Texts.NAV_FACTORY, selected = section == AppSection.BOARD) { onSelect(AppSection.BOARD) }
            if (feedAvailable) NavItem(Texts.FEED_TITLE, selected = section == AppSection.FEED) { onSelect(AppSection.FEED) }
            if (onSettings != null) NavItem(Texts.NAV_SETTINGS, selected = false, onClick = onSettings)
        }
        Spacer(Modifier.weight(1f))
        SearchButton(onSearch)
        ThemeSwitch(theme, onTheme)
    }
}

@Composable
private fun Logo() {
    val color = Tokens.text
    Box(
        Modifier.size(18.dp).drawBehind {
            val path = Path().apply {
                moveTo(size.width / 2f, size.height * 0.1f)
                lineTo(size.width * 0.95f, size.height * 0.9f)
                lineTo(size.width * 0.05f, size.height * 0.9f)
                close()
            }
            drawIntoCanvas { it.drawPath(path, Paint().apply { this.color = color }) }
        },
    )
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        modifier = Modifier
            .height(32.dp)
            .background(if (selected) Tokens.raise else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = TextStyle(
            fontFamily = sans,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Tokens.text else Tokens.muted,
        ),
    )
}

@Composable
private fun SearchButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(280.dp)
            .height(32.dp)
            .border(1.dp, Tokens.edge, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            Texts.SEARCH_HINT,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.faint),
        )
        BasicText(
            Texts.SEARCH_SHORTCUT,
            modifier = Modifier.border(1.dp, Tokens.edge, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            style = TextStyle(fontFamily = mono, fontSize = 11.sp, color = Tokens.faint),
        )
    }
}

/** Cards that wait for the owner, counted from the board. */
fun waitingCount(view: BoardView?): Int =
    view?.board?.cards?.count { isWaitingForOwner(it.column) } ?: 0

/**
 * The factory view: what the factory says, the time split and the board with the optional card panel beside it.
 * [sidePanel] draws the open card at the width it is given; it is empty when no card is open.
 */
@Composable
fun FactoryScreen(
    view: BoardView?,
    pulse: PulseView,
    connected: Boolean,
    controller: OwnerMoveController? = null,
    onOpenSettings: (() -> Unit)? = null,
    onReload: (suspend () -> ResyncResult)? = null,
    openCardId: String? = null,
    focusReturnCardId: String? = null,
    onOpenCard: (String) -> Unit = {},
    panelWidth: Dp = 0.dp,
    sidePanel: @Composable (Dp) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        PulseStrip(pulse, waiting = waitingCount(view))
        view?.let { shown ->
            TimeSplit.of(shown.columnTimes)?.let { split ->
                FlowBar(split, dimmed = !shown.live, modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp))
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val width = minOf(panelWidth, maxWidth)
            val board: @Composable () -> Unit = {
                BoardScreen(
                    view,
                    connected = connected,
                    controller = controller,
                    onOpenSettings = onOpenSettings,
                    onReload = onReload,
                    openCardId = openCardId,
                    focusReturnCardId = focusReturnCardId,
                    onOpenCard = onOpenCard,
                )
            }
            when (panelPlacement(maxWidth.value.toInt())) {
                PanelPlacement.SIDE_BY_SIDE -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { board() }
                    sidePanel(width)
                }
                PanelPlacement.OVERLAY -> Box(Modifier.fillMaxSize()) {
                    board()
                    Box(Modifier.align(Alignment.CenterEnd)) { sidePanel(width) }
                }
            }
        }
    }
}

