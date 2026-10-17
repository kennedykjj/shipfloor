package shipfloor.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Clock
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import shipfloor.core.ColumnTime
import shipfloor.core.Column as BoardColumn

private const val ID_LENGTH = 8
private const val LANE_GAP = 0
private val MAP_BOX_MIN_WIDTH = 12.dp
private val MAP_COUNT_MIN_WIDTH = 24.dp
private val EDGE_FADE_WIDTH = 24.dp

/** The two lanes of the board: the columns up to the approved spec, then from the code to Done. */
internal val LANE_ONE = listOf(
    BoardColumn.INBOX, BoardColumn.READY_FOR_REFINEMENT, BoardColumn.REFINEMENT, BoardColumn.SPEC_REVIEW, BoardColumn.READY_FOR_DEV,
)
internal val LANE_TWO = listOf(
    BoardColumn.CODING, BoardColumn.VALIDATION, BoardColumn.STAGED, BoardColumn.READY_FOR_PROD, BoardColumn.DONE,
)
private const val CARDS_SHOWN = 4
private const val CHIPS_SHOWN = 9
private val COLUMN_MIN_HEIGHT = 112.dp

/**
 * The board: ten columns in product order, laid out in two lanes of five so that all of them are in view and an
 * empty one is still a column. [view] is null until the first snapshot; [connected] false dims the last known state
 * and says so. With a [controller] the owner moves cards (drag, `M` on a focused card, or the buttons of a card that
 * waits for a decision); without one the board is read only. Titles are external text: drawn as plain text, never
 * interpreted.
 */
@Composable
fun BoardScreen(
    view: BoardView?,
    connected: Boolean,
    controller: OwnerMoveController? = null,
    onOpenSettings: (() -> Unit)? = null,
    onReload: (suspend () -> ResyncResult)? = null,
    openCardId: String? = null,
    focusReturnCardId: String? = null,
    onOpenCard: (String) -> Unit = {},
    onShowAll: (BoardColumn) -> Unit = {},
    density: BoardDensity = BoardDensity.CARDS,
    onDensity: (BoardDensity) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var reloading by remember { mutableStateOf(false) }
    var reloadFailed by remember { mutableStateOf(false) }
    val laneBounds = remember { mutableStateMapOf<BoardColumn, Rect>() }
    val move by (controller?.state ?: remember { MutableStateFlow(OwnerMoveState()) }).collectAsState()
    val scrollState = rememberScrollState()
    var laneTwoTop by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier.fillMaxSize().background(Tokens.ground).padding(top = 12.dp).semantics { contentDescription = Texts.BOARD },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connected) StaleBanner()
            if (connected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LayoutControls(density, onDensity)
                    if (onReload != null) {
                        ReloadButton(reloading) {
                            if (!reloading) {
                                reloading = true
                                reloadFailed = false
                                scope.launch {
                                    try {
                                        reloadFailed = onReload() == ResyncResult.Failed
                                    } finally {
                                        reloading = false
                                    }
                                }
                            }
                        }
                    }
                    if (onOpenSettings != null) SettingsButton(onOpenSettings)
                }
            }
            if (reloadFailed) {
                Row(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(Texts.RELOAD_FAILED, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.warn))
                    IconButton(AppIcon.CLOSE, Texts.DISMISS) { reloadFailed = false }
                }
            }
            move.status?.let { StatusLine(it) }
            move.notice?.let { notice -> NoticeLine(notice) { controller?.dismissNotice() } }
            val emptyBoard = view?.board
            if (emptyBoard != null && emptyBoard.cards.isEmpty()) {
                BasicText(Texts.EMPTY_BOARD, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft))
            }
            val pending = move.held
            if (controller != null && pending != null) {
                if (move.notePending) {
                    NoteBox(
                        onSend = { note -> scope.launch { controller.submit(note) } },
                        onCancel = controller::cancel,
                    )
                } else {
                    DestinationChooser(pending, move, controller, scope)
                }
            }
        }
        val board = view?.board
        val held = move.held
        val counts = BoardColumn.entries.associateWith { board?.cardsIn(it)?.size ?: 0 }
        Column(modifier = Modifier.fillMaxSize().alpha(if (connected) 1f else 0.55f)) {
            Box(Modifier.padding(horizontal = 20.dp)) {
                ColumnStrip(
                    counts = counts,
                    enabled = connected,
                    onGo = { column ->
                        scope.launch { scrollState.animateScrollTo(if (column in LANE_ONE) 0 else laneTwoTop) }
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawLine(Tokens.edge, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                    .verticalScroll(scrollState),
            ) {
                val waiting = (counts[BoardColumn.SPEC_REVIEW] ?: 0) + (counts[BoardColumn.STAGED] ?: 0)
                LaneGroup(
                    title = Texts.LANE_REFINE,
                    subtitle = Texts.LANE_REFINE_SUB,
                    count = LANE_ONE.sumOf { counts.getValue(it) }.toString(),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(Texts.LANE_HAND_OFF, style = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tokens.faint))
                            IconGlyph(AppIcon.ARROW_DOWN, Tokens.faint, size = 13.dp)
                        }
                    },
                ) {
                    LANE_ONE.forEachIndexed { index, column ->
                        LaneColumn(
                            column, index, board?.cardsIn(column), view?.columnTimes?.get(column), density, controller, held, move,
                            laneBounds, scope, openCardId, focusReturnCardId, onOpenCard, onShowAll,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                LaneGroup(
                    title = Texts.LANE_DELIVER,
                    subtitle = Texts.LANE_DELIVER_SUB,
                    count = "${LANE_TWO.filter { it != BoardColumn.DONE }.sumOf { counts.getValue(it) }} + ${counts.getValue(BoardColumn.DONE)}",
                    trailing = {
                        if (waiting > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(Tokens.accent, CircleShape))
                                BasicText(Texts.waitingForYou(waiting), style = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tokens.sand))
                            }
                        }
                    },
                    modifier = Modifier.onGloballyPositioned { laneTwoTop = it.positionInParent().y.roundToInt() },
                    last = true,
                ) {
                    LANE_TWO.forEachIndexed { index, column ->
                        LaneColumn(
                            column, index, board?.cardsIn(column), view?.columnTimes?.get(column), density, controller, held, move,
                            laneBounds, scope, openCardId, focusReturnCardId, onOpenCard, onShowAll,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** The short name of a column in the strip; the full name stays in the lane header and in the description. */
internal fun stripLabel(column: BoardColumn): String =
    when (column) {
        BoardColumn.READY_FOR_REFINEMENT, BoardColumn.READY_FOR_DEV -> "Ready"
        BoardColumn.READY_FOR_PROD -> "Prod"
        else -> column.displayName
    }

/** One cell per column with its count and name; a click scrolls to the lane of the column. */
@Composable
private fun ColumnStrip(counts: Map<BoardColumn, Int>, enabled: Boolean, onGo: (BoardColumn) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(Tokens.panel, shape)
            .border(1.dp, Tokens.edge, shape)
            .semantics { contentDescription = Texts.COLUMN_MAP },
    ) {
        BoardColumn.entries.forEachIndexed { index, column ->
            StripCell(column, counts.getValue(column), divider = index > 0, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight()) { onGo(column) }
        }
    }
}

@Composable
private fun StripCell(column: BoardColumn, count: Int, divider: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val waiting = isWaitingForOwner(column) && count > 0
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .drawBehind {
                if (divider) drawLine(Tokens.edge, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx())
                if (focused) drawRect(Tokens.accent, style = Stroke(2.dp.toPx()))
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = Texts.goToColumn(column, count) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                count.toString(),
                style = TextStyle(
                    fontFamily = sans,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (count == 0) Tokens.faint else Tokens.text,
                    fontFeatureSettings = "tnum",
                ),
            )
            BasicText(
                stripLabel(column),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (waiting) Tokens.sand else Tokens.muted),
            )
        }
        if (waiting) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(Tokens.accent, CircleShape))
    }
}

/** A lane: its bar (name, what it covers, count, a note on the right) and the row of its five columns. */
@Composable
private fun LaneGroup(
    title: String,
    subtitle: String,
    count: String,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    columns: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) drawLine(Tokens.edge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 11.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(title, style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
            BasicText(subtitle, style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, color = Tokens.faint))
            CountPill(count)
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), content = columns)
    }
}

@Composable
private fun CountPill(text: String) {
    BasicText(
        text,
        modifier = Modifier.background(Tokens.raise, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
        style = TextStyle(fontFamily = mono, fontSize = 11.5.sp, color = Tokens.muted, fontFeatureSettings = "tnum"),
    )
}

@Composable
private fun StaleBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Tokens.warn, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(Tokens.warn, CircleShape))
        BasicText(Texts.STALE_BOARD, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.text))
    }
}

@Composable
private fun StatusLine(text: String) {
    BasicText(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted),
    )
}

@Composable
private fun ReloadButton(reloading: Boolean, onClick: () -> Unit) {
    IconButton(AppIcon.REFRESH, if (reloading) Texts.RELOADING else Texts.RELOAD, enabled = !reloading, onClick = onClick)
}

@Composable
private fun NoticeLine(notice: MoveNotice, onDismiss: () -> Unit) {
    val color = if (notice.kind == NoticeKind.REFUSED) Tokens.bad else Tokens.warn
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(notice.text, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = color))
        IconButton(AppIcon.CLOSE, Texts.DISMISS, onClick = onDismiss)
    }
}

/** The keyboard chooser opened by `M`: arrows pick, Enter sends, Esc cancels. */
@Composable
private fun DestinationChooser(
    held: BoardCard,
    move: OwnerMoveState,
    controller: OwnerMoveController,
    scope: CoroutineScope,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(held.id) {
        if (move.selected == null) move.targets.firstOrNull()?.let(controller::chooseTarget)
        focus.requestFocus()
    }
    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .background(Tokens.raise, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .claimsEscape()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val index = move.targets.indexOf(move.selected)
                when (event.key) {
                    Key.DirectionRight, Key.DirectionDown -> move.targets.getOrNull((index + 1) % move.targets.size)?.let(controller::chooseTarget)
                    Key.DirectionLeft, Key.DirectionUp -> move.targets.getOrNull((index - 1 + move.targets.size) % move.targets.size)?.let(controller::chooseTarget)
                    Key.Enter -> scope.launch { controller.submit() }
                    Key.Escape -> controller.cancel()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        move.targets.forEach { target ->
            val selected = target == move.selected
            BasicText(
                Texts.moveMeaning(held.column, target) + " → " + target.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(if (selected) 2.dp else 1.dp, if (selected) Tokens.edgeHi else Tokens.edge, RoundedCornerShape(6.dp))
                    .clickable { controller.chooseTarget(target) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
            )
        }
    }
}

/** The mandatory note of a spec sent back; Enter sends, Shift+Enter breaks the line, Esc cancels. */
@Composable
private fun NoteBox(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val blank = text.isBlank()
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier = Modifier.widthIn(max = 320.dp).background(Tokens.raise, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(Texts.NOTE_LABEL, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.soft))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            cursorBrush = SolidColor(Tokens.text),
            textStyle = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Tokens.edgeHi, RoundedCornerShape(6.dp))
                .padding(6.dp)
                .claimsEscape()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> onCancel()
                        event.key == Key.Enter && !event.isShiftPressed -> if (!blank) onSend(text)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(Texts.SEND_BACK, AppIcon.SEND, ButtonKind.PRIMARY, enabled = !blank) { onSend(text) }
            AppButton(Texts.CANCEL, AppIcon.CLOSE, onClick = onCancel)
        }
    }
}

/** One column of a lane: header, then its cards, a dashed box when it is empty and a target while a card is held. */
@Composable
private fun LaneColumn(
    column: BoardColumn,
    index: Int,
    cards: List<BoardCard>?,
    time: ColumnTime?,
    density: BoardDensity,
    controller: OwnerMoveController?,
    held: BoardCard?,
    move: OwnerMoveState,
    laneBounds: MutableMap<BoardColumn, Rect>,
    scope: CoroutineScope,
    openCardId: String?,
    focusReturnCardId: String?,
    onOpenCard: (String) -> Unit,
    onShowAll: (BoardColumn) -> Unit,
    modifier: Modifier,
) {
    val count = cards?.size
    val waiting = isWaitingForOwner(column) && !cards.isNullOrEmpty()
    val accepts = held != null && column in move.targets
    val dimmed = held != null && !accepts && held.column != column
    val timeLabel = time?.let(ColumnTimeLabel::of)
    val chips = density == BoardDensity.CHIPS
    val accent = Tokens.accent
    val ground = Tokens.ground
    val edge = Tokens.edge
    val faint = Tokens.faint
    Column(
        modifier = modifier
            .heightIn(min = COLUMN_MIN_HEIGHT)
            .alpha(if (dimmed) 0.4f else 1f)
            .onGloballyPositioned { laneBounds[column] = it.boundsInRoot() }
            .background(if (accepts) accent.copy(alpha = 0.09f) else if (waiting) accent.copy(alpha = 0.05f) else Color.Transparent)
            .drawBehind {
                if (index < 4) drawLine(edge, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
                if (waiting) drawRect(accent, size = Size(size.width, 2.dp.toPx()))
                if (index > 0) drawFlowChevron(ground, edge, faint)
            }
            .semantics { contentDescription = laneDescription(column, count ?: 0, waiting, timeLabel) },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 14.dp),
        ) {
            if (waiting) Box(Modifier.size(7.dp).background(Tokens.accent, CircleShape))
            BasicText(
                column.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (waiting) Tokens.sand else Tokens.muted),
            )
            CountPill(count?.toString() ?: "–")
        }
        if (!chips) {
            Box(Modifier.height(15.dp).padding(start = 14.dp)) {
                timeLabel?.let { Meta(it, Tokens.faint) }
            }
            Spacer(Modifier.height(6.dp))
        }
        val gap = if (chips) 0.dp else 9.dp
        val sidePad = if (chips) 0.dp else 12.dp
        Column(
            Modifier.fillMaxWidth().padding(start = sidePad, end = sidePad, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            // Done lists its newest first. A column keeps its size: the first few cards, then a "+N" card.
            val ordered = if (column == BoardColumn.DONE) cards?.asReversed() else cards
            val limit = if (chips) CHIPS_SHOWN else CARDS_SHOWN
            val shown = ordered?.take(limit)
            when {
                cards == null -> Meta(Texts.LOADING_BOARD, Tokens.faint, Modifier.padding(start = if (chips) 14.dp else 2.dp))
                cards.isEmpty() && !accepts -> EmptyBox(Texts.EMPTY_COLUMN)
                else -> shown.orEmpty().forEach {
                    if (chips) {
                        Chip(it, it.id == openCardId, it.id == focusReturnCardId, onOpenCard)
                    } else {
                        Card(it, waiting, controller, laneBounds, scope, it.id == openCardId, it.id == focusReturnCardId, onOpenCard)
                    }
                }
            }
            val hidden = (ordered?.size ?: 0) - limit
            if (hidden > 0) MoreCard(hidden, column, chips) { onShowAll(column) }
            if (accepts) EmptyBox(Texts.DROP_HERE, highlighted = true, modifier = Modifier.padding(horizontal = if (chips) 12.dp else 0.dp, vertical = if (chips) 8.dp else 0.dp))
        }
    }
}

/** The little arrow on the divider that says the flow goes on to this column. */
private fun DrawScope.drawFlowChevron(fill: Color, border: Color, mark: Color) {
    val radius = 8.dp.toPx()
    val center = Offset(0f, 18.dp.toPx())
    drawCircle(fill, radius, center)
    drawCircle(border, radius, center, style = Stroke(1.dp.toPx()))
    val path = Path().apply {
        moveTo(center.x - 1.8.dp.toPx(), center.y - 3.2.dp.toPx())
        lineTo(center.x + 1.8.dp.toPx(), center.y)
        lineTo(center.x - 1.8.dp.toPx(), center.y + 3.2.dp.toPx())
    }
    drawPath(path, mark, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The last card of a full column: how many more there are, and a click opens them all. */
@Composable
private fun MoreCard(count: Int, column: BoardColumn, chip: Boolean, onClick: () -> Unit) {
    val description = Texts.showAllIn(column, count)
    if (chip) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconGlyph(AppIcon.ARROW_DOWN, Tokens.faint, size = 13.dp)
            BasicText(Texts.moreCards(count), style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Tokens.muted))
        }
        return
    }
    val edge = Tokens.edgeHi
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    edge,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("+$count", style = TextStyle(fontFamily = sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text, fontFeatureSettings = "tnum"))
        Column {
            BasicText(Texts.CARDS_WORD, style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, color = Tokens.muted))
            BasicText(Texts.SEE_ALL, style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, color = Tokens.muted))
        }
    }
}

/** A dashed box: "vazia" in an empty column, "soltar aqui" in the column a held card can go to. */
@Composable
private fun EmptyBox(text: String, highlighted: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (highlighted) Tokens.sand else Tokens.faint
    val line = if (highlighted) Tokens.accent else Tokens.edgeHi
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (highlighted) 42.dp else 50.dp)
            .drawBehind {
                drawRoundRect(
                    line,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = color))
    }
}

/** A chip is a list row: the state glyph and the title on one line, no box; the full face is the accessibility description. */
@Composable
internal fun Chip(card: BoardCard, open: Boolean, takesFocus: Boolean, onOpenCard: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(takesFocus) { if (takesFocus) focus.requestFocus() }
    val waiting = isWaitingForOwner(card.column)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (open) Tokens.raise else Color.Transparent)
            .drawBehind {
                drawLine(Tokens.edge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                if (focused) drawRect(Tokens.accent, style = Stroke(2.dp.toPx()))
                if (waiting) drawRect(Tokens.accent, size = Size(2.dp.toPx(), size.height))
            }
            .semantics { contentDescription = CardFace.of(card, Clock.systemUTC()).description }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button) { onOpenCard(card.id) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusGlyph(glyphOf(card.column, card.blocked), size = 11.dp)
        BasicText(
            card.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = sans, fontSize = 12.5.sp, fontWeight = FontWeight.Normal, color = if (waiting) Tokens.sand else Tokens.soft),
        )
    }
}

/** The density switch: cards or chips, as icons with tooltips. */
@Composable
private fun LayoutControls(density: BoardDensity, onDensity: (BoardDensity) -> Unit) {
    IconSegmented(
        listOf(AppIcon.CARDS to Texts.DENSITY_CARDS, AppIcon.ROWS to Texts.DENSITY_CHIPS),
        selected = if (density == BoardDensity.CARDS) 0 else 1,
    ) { onDensity(if (it == 0) BoardDensity.CARDS else BoardDensity.CHIPS) }
}

@Composable
private fun Meta(text: String, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = color))
}

@Composable
private fun Card(
    card: BoardCard,
    waiting: Boolean,
    controller: OwnerMoveController?,
    laneBounds: Map<BoardColumn, Rect>,
    scope: CoroutineScope,
    open: Boolean,
    takesFocus: Boolean,
    onOpenCard: (String) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val shortId = card.id.take(ID_LENGTH)
    val movable = controller != null && ownerTargets(card.column).isNotEmpty()
    var focused by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val face = CardFace.of(card, Clock.systemUTC())
    val description = face.description + (if (movable) ", pode mover" else "") + (if (open) ", ${Texts.openCardHint()}" else "")
    val focus = remember { FocusRequester() }
    LaunchedEffect(takesFocus) { if (takesFocus) focus.requestFocus() }
    val glyph = glyphOf(card.column, card.blocked)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .background(if (open) Tokens.raise else Tokens.panel, shape)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Tokens.accent else if (open) Tokens.text else if (waiting) Tokens.sand.copy(alpha = 0.6f) else Tokens.edge,
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .semantics { contentDescription = description }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button) { onOpenCard(card.id) }
            .onPreviewKeyEvent { event ->
                if (controller != null && event.type == KeyEventType.KeyDown && event.key == Key.M) {
                    controller.hold(card.id)
                    true
                } else {
                    false
                }
            }
            .pointerInput(card.id, card.column, controller) {
                if (controller == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { start ->
                        pointer = origin + start
                        controller.hold(card.id)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        pointer += amount
                    },
                    onDragCancel = controller::cancel,
                    onDragEnd = {
                        val target = laneBounds.entries.firstOrNull { pointer in it.value }?.key
                        if (target != null && target in controller.state.value.targets) {
                            controller.chooseTarget(target)
                            scope.launch { controller.submit() }
                        } else {
                            controller.cancel()
                        }
                    },
                )
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardBody(card, face, glyph)
        if (controller != null && waiting) DecisionButtons(card, controller, scope)
    }
}

/** The inside of a card: the state glyph and the number, the title, then the age and the cost. */
@Composable
internal fun CardBody(card: BoardCard, face: CardFace, glyph: CardGlyph) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(glyph, size = 16.dp)
            BasicText(card.id.take(ID_LENGTH), style = TextStyle(fontFamily = mono, fontSize = 11.sp, color = Tokens.faint))
            face.blockedLabel?.let { BasicText(it, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.bad)) }
        }
        BasicText(
            card.title,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Tokens.text, lineHeight = 20.sp),
        )
        val costKnown = card.cost?.let { it.costUsd != null && (it.status == CostStatus.KNOWN || it.status == CostStatus.PARTIAL) } == true
        val cost = listOfNotNull(face.cost.takeIf { costKnown }, face.costNote.takeIf { costKnown }).joinToString(" · ")
        if (face.age != null || cost.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().drawBehind { drawLine(Tokens.edge, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(face.age.orEmpty(), style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.muted, fontFeatureSettings = "tnum"))
                BasicText(cost, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.muted, fontFeatureSettings = "tnum"))
            }
        }
    }
}

/** Approve (or release) and ask for a change, for a card that waits for the owner; both go through the owner's move. */
@Composable
internal fun DecisionButtons(card: BoardCard, controller: OwnerMoveController, scope: CoroutineScope) {
    val spec = card.column == BoardColumn.SPEC_REVIEW
    val approveTo = if (spec) BoardColumn.READY_FOR_DEV else BoardColumn.READY_FOR_PROD
    val changeTo = if (spec) BoardColumn.READY_FOR_REFINEMENT else BoardColumn.CODING
    fun decide(to: BoardColumn) {
        controller.hold(card.id)
        controller.chooseTarget(to)
        scope.launch { controller.submit() }
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(if (spec) Texts.APPROVE else Texts.RELEASE, AppIcon.CHECK, ButtonKind.ACCENT) { decide(approveTo) }
        AppButton(Texts.REQUEST_CHANGE, AppIcon.UNDO) { decide(changeTo) }
    }
}

/** The color of a stage hue; the accent is the sand of the owner-decision columns, neutral is plain muted text. */
internal fun hueColor(hue: StageHue): Color =
    when (hue) {
        StageHue.REFINE -> Tokens.refine
        StageHue.DEVELOP -> Tokens.develop
        StageHue.VALIDATE -> Tokens.validate
        StageHue.OK -> Tokens.ok
        StageHue.ACCENT -> Tokens.sand
        StageHue.NEUTRAL -> Tokens.muted
    }

private fun laneDescription(column: BoardColumn, count: Int, waiting: Boolean, timeLabel: String?): String {
    val base = "${column.displayName}, ${Texts.cardCount(count)}"
    val withTime = if (timeLabel != null) "$base, $timeLabel" else base
    return if (waiting) "$withTime, ${Texts.WAITS_FOR_YOU.replaceFirstChar { it.lowercase() }}" else withTime
}
