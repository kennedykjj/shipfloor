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
    initialLayout: ColumnLayoutState = ColumnLayoutState(),
) {
    val scope = rememberCoroutineScope()
    var reloading by remember { mutableStateOf(false) }
    var reloadFailed by remember { mutableStateOf(false) }
    val laneBounds = remember { mutableStateMapOf<BoardColumn, Rect>() }
    val move by (controller?.state ?: remember { MutableStateFlow(OwnerMoveState()) }).collectAsState()
    val scrollState = rememberScrollState()
    var viewportPx by remember { mutableStateOf(0) }
    var layout by remember { mutableStateOf(initialLayout) }
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
                    LayoutControls(layout) { layout = it }
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
                    BasicText(
                        Texts.DISMISS,
                        modifier = Modifier.clickable { reloadFailed = false },
                        style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.soft),
                    )
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
        val density = LocalDensity.current.density
        val viewportDp = (viewportPx / density).roundToInt()
        val counts = BoardColumn.entries.associateWith { board?.cardsIn(it)?.size ?: 1 }
        val widths = columnWidths(layout, counts, viewportDp, LANE_GAP)
        Column(modifier = Modifier.fillMaxSize().alpha(if (connected) 1f else 0.55f)) {
            Box(Modifier.padding(horizontal = 20.dp)) {
                ColumnStrip(
                    map = boardMap(
                        board ?: Board(emptyList(), null),
                        widths,
                        LANE_GAP,
                        viewportDp,
                        (scrollState.value / density).roundToInt(),
                    ),
                    enabled = connected,
                    onGo = { column ->
                        val target = scrollTarget(column, widths, LANE_GAP, viewportDp)
                        scope.launch { scrollState.scrollTo((target * density).roundToInt()) }
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawLine(Tokens.edge, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                    .onSizeChanged { viewportPx = it.width },
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(LANE_GAP.dp),
                ) {
                    BoardColumn.entries.forEach { column ->
                        val accepts = held != null && column in move.targets
                        Lane(
                            width = widths.getValue(column),
                            layout = layout,
                            onLayout = { layout = it },
                            column = column,
                            cards = board?.cardsIn(column),
                            time = view?.columnTimes?.get(column),
                            controller = controller,
                            accepts = accepts,
                            dimmed = held != null && !accepts,
                            laneBounds = laneBounds,
                            scope = scope,
                            openCardId = openCardId,
                            focusReturnCardId = focusReturnCardId,
                            onOpenCard = onOpenCard,
                        )
                    }
                }
                EdgeFadeOverlays(scrollState)
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

/** One cell per column with its count and name; the cells in view carry a line, and a click scrolls to the column. */
@Composable
private fun ColumnStrip(map: BoardMap, enabled: Boolean, onGo: (BoardColumn) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val total = map.boxes.sumOf { it.share.toDouble() }.toFloat().coerceAtLeast(Float.MIN_VALUE)
    var cursor = 0f
    val inView = map.boxes.map { box ->
        val from = cursor / total
        cursor += box.share
        val to = cursor / total
        from < map.viewStart + map.viewShare && to > map.viewStart
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(Tokens.panel, shape)
            .border(1.dp, Tokens.edge, shape)
            .semantics { contentDescription = Texts.COLUMN_MAP },
    ) {
        map.boxes.forEachIndexed { index, box ->
            StripCell(box, inView[index], divider = index > 0, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight()) { onGo(box.column) }
        }
    }
}

@Composable
private fun StripCell(box: MapBox, inView: Boolean, divider: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val waiting = isWaitingForOwner(box.column) && box.count > 0
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .drawBehind {
                if (divider) drawLine(Tokens.edge, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx())
                if (inView) drawLine(Tokens.text.copy(alpha = 0.55f), Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                if (focused) drawRect(Tokens.accent, style = Stroke(2.dp.toPx()))
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = Texts.goToColumn(box.column, box.count) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                box.count.toString(),
                style = TextStyle(
                    fontFamily = sans,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (box.count == 0) Tokens.faint else Tokens.text,
                    fontFeatureSettings = "tnum",
                ),
            )
            BasicText(
                stripLabel(box.column),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (waiting) Tokens.sand else Tokens.muted),
            )
        }
        if (waiting) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(Tokens.accent, CircleShape))
    }
}



/** Gradient strips over the board edges, only on the side that has more to see; they never take clicks. */
@Composable
private fun BoxScope.EdgeFadeOverlays(scrollState: ScrollState) {
    val fades = edgeFades(scrollState.value, scrollState.maxValue)
    if (fades.start) EdgeFade(Modifier.align(Alignment.CenterStart), Brush.horizontalGradient(listOf(Tokens.ground, Color.Transparent)))
    if (fades.end) EdgeFade(Modifier.align(Alignment.CenterEnd), Brush.horizontalGradient(listOf(Color.Transparent, Tokens.ground)))
}

@Composable
private fun EdgeFade(modifier: Modifier, brush: Brush) {
    Box(modifier.width(EDGE_FADE_WIDTH).fillMaxHeight().background(brush))
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
        BasicText(
            Texts.DISMISS,
            modifier = Modifier.clickable(onClick = onDismiss),
            style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.soft),
        )
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(
                Texts.SEND_BACK,
                modifier = Modifier
                    .alpha(if (blank) 0.45f else 1f)
                    .semantics { if (blank) contentDescription = Texts.SEND_BACK + " (desabilitado)" }
                    .clickable(enabled = !blank) { onSend(text) },
                style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
            )
            BasicText(
                Texts.CANCEL,
                modifier = Modifier.clickable(onClick = onCancel),
                style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft),
            )
        }
    }
}

@Composable
private fun Lane(
    width: Int,
    layout: ColumnLayoutState,
    onLayout: (ColumnLayoutState) -> Unit,
    column: BoardColumn,
    cards: List<BoardCard>?,
    time: ColumnTime?,
    controller: OwnerMoveController?,
    accepts: Boolean,
    dimmed: Boolean,
    laneBounds: MutableMap<BoardColumn, Rect>,
    scope: CoroutineScope,
    openCardId: String?,
    focusReturnCardId: String?,
    onOpenCard: (String) -> Unit,
) {
    val waiting = isWaitingForOwner(column) && !cards.isNullOrEmpty()
    val count = cards?.size
    val timeLabel = time?.let(ColumnTimeLabel::of)
    if (width == ColumnLayoutState.COLLAPSED_WIDTH && count == 0 && column !in layout.restored) {
        CollapsedLane(column, laneBounds) { onLayout(layout.restore(column)) }
        return
    }
    val chips = layout.density == BoardDensity.CHIPS
    Column(
        modifier = Modifier
            .width(width.dp)
            .fillMaxHeight()
            .alpha(if (dimmed) 0.45f else 1f)
            .onGloballyPositioned { laneBounds[column] = it.boundsInRoot() }
            .background(if (accepts) Tokens.raise else Color.Transparent)
            .drawBehind { drawLine(Tokens.edge, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) }
            .semantics { contentDescription = laneDescription(column, count ?: 0, waiting, timeLabel) },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp),
        ) {
            BasicText(
                column.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (waiting) Tokens.sand else Tokens.muted),
            )
            BasicText(
                count?.toString() ?: "–",
                style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.faint, fontFeatureSettings = "tnum"),
            )
            ResizeHandle(column, width, onLayout, layout)
        }
        if (!chips) {
            timeLabel?.let { Meta(it, Tokens.faint, Modifier.padding(start = 14.dp, bottom = 6.dp)) }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = if (chips) 0.dp else 10.dp, end = if (chips) 0.dp else 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (chips) 0.dp else 10.dp),
        ) {
            when {
                cards == null -> Meta(Texts.LOADING_BOARD, Tokens.faint)
                cards.isEmpty() -> Meta(Texts.EMPTY_COLUMN, Tokens.faint, Modifier.padding(start = 4.dp))
                else -> cards.forEach {
                    if (chips) {
                        Chip(it, it.id == openCardId, it.id == focusReturnCardId, onOpenCard)
                    } else {
                        Card(it, waiting, controller, laneBounds, scope, it.id == openCardId, it.id == focusReturnCardId, onOpenCard)
                    }
                }
            }
        }
    }
}

/** An empty column folded to a strip: count on top, name rotated below; click or Enter/Space restores it. */
@Composable
private fun CollapsedLane(column: BoardColumn, laneBounds: MutableMap<BoardColumn, Rect>, onRestore: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(ColumnLayoutState.COLLAPSED_WIDTH.dp)
            .fillMaxHeight()
            .onGloballyPositioned { laneBounds[column] = it.boundsInRoot() }
            .drawBehind {
                drawLine(Tokens.edge, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
                if (focused) drawRect(Tokens.accent, style = Stroke(2.dp.toPx()))
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onRestore)
            .clearAndSetSemantics { contentDescription = Texts.restoreColumn(column) }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("0", style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.faint, fontFeatureSettings = "tnum"))
        BasicText(
            column.displayName,
            maxLines = 1,
            modifier = Modifier.vertical(),
            style = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tokens.faint),
        )
    }
}

/** Rotates the text a quarter turn so a column name reads bottom to top inside the strip. */
private fun Modifier.vertical(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(maxWidth = Int.MAX_VALUE))
        layout(placeable.height, placeable.width) {
            placeable.placeWithLayer(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2) { rotationZ = -90f }
        }
    }

/** A chip is a list row: the state glyph and the title on one line, no box; the full face is the accessibility description. */
@Composable
private fun Chip(card: BoardCard, open: Boolean, takesFocus: Boolean, onOpenCard: (String) -> Unit) {
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

/** The grip in the column header: drag, double click to reset, Left/Right to nudge by [ColumnLayoutState.KEY_STEP]. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResizeHandle(column: BoardColumn, width: Int, onLayout: (ColumnLayoutState) -> Unit, layout: ColumnLayoutState) {
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val latestWidth by rememberUpdatedState(width)
    val latestLayout by rememberUpdatedState(layout)
    Box(
        modifier = Modifier
            .size(24.dp)
            .border(if (focused) 2.dp else 0.dp, if (focused) Tokens.accent else Color.Transparent, RoundedCornerShape(6.dp))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { role = Role.Button; contentDescription = Texts.resizeHandle(column) }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = when (event.key) {
                    Key.DirectionLeft -> -ColumnLayoutState.KEY_STEP
                    Key.DirectionRight -> ColumnLayoutState.KEY_STEP
                    else -> return@onKeyEvent false
                }
                onLayout(latestLayout.resize(column, latestWidth + step))
                true
            }
            .pointerInput(column) {
                detectTapGestures(onDoubleTap = { onLayout(latestLayout.reset(column)) })
            }
            .pointerInput(column) {
                var dragWidth = 0f
                detectDragGestures(
                    onDragStart = { dragWidth = latestWidth.toFloat() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragWidth += amount.x / density
                        onLayout(latestLayout.resize(column, dragWidth.roundToInt()))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 2.dp, height = 14.dp).background(if (hovered || focused) Tokens.soft else Tokens.edgeHi, RoundedCornerShape(1.dp)))
    }
}

/** Fit to window and the density switch; a column goes back to its default width with a double click on its grip. */
@Composable
private fun LayoutControls(layout: ColumnLayoutState, onLayout: (ColumnLayoutState) -> Unit) {
    LabeledSwitch(Texts.FIT_TO_WINDOW, layout.fit) { onLayout(layout.withFit(it)) }
    SegmentedControl(
        listOf(Texts.DENSITY_CARDS, Texts.DENSITY_CHIPS),
        selected = if (layout.density == BoardDensity.CARDS) 0 else 1,
    ) { onLayout(layout.withDensity(if (it == 0) BoardDensity.CARDS else BoardDensity.CHIPS)) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(glyph, size = 16.dp)
            BasicText(shortId, style = TextStyle(fontFamily = mono, fontSize = 11.sp, color = Tokens.faint))
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
