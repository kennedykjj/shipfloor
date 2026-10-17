package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import shipfloor.core.Column as BoardColumn

private val OWNER_DECISIONS = setOf("OWNER_APPROVED", "OWNER_CHANGE_REQUESTED")
private const val DONE_COLUMN = "DONE"
private const val PASS = "PASS"
private const val FAIL = "FAIL"
private const val NO_GATE = "none"
private val INSTANT_FORMAT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("pt-BR"))

/** `16 out 14:32`, in the given zone. */
fun formatInstant(at: Instant, zone: ZoneId): String =
    INSTANT_FORMAT.format(at.atZone(zone)).replace(".", "")

/** An approval or change request signed by the owner, as opposed to an event written by an agent or the factory. */
internal fun TimelineEvent.isOwnerDecision(): Boolean = type in OWNER_DECISIONS && actor == "owner"

private fun body(color: Color = Tokens.soft) = TextStyle(fontFamily = sans, fontSize = 14.sp, color = color)
private fun meta(color: Color = Tokens.muted) = TextStyle(fontFamily = sans, fontSize = 13.sp, color = color)
private fun tabular(style: TextStyle) = style.copy(fontFeatureSettings = "tnum")

/** Tells the window that a text field (or the move chooser) owns Esc while it has focus. */
class EscapeClaim {
    var textFieldFocused by mutableStateOf(false)
}

val LocalEscapeClaim = compositionLocalOf { EscapeClaim() }

/** Marks the node as an Esc owner while it is focused, so the window does not close the side panel under it. */
@Composable
fun Modifier.claimsEscape(): Modifier {
    val claim = LocalEscapeClaim.current
    DisposableEffect(claim) { onDispose { claim.textFieldFocused = false } }
    return onFocusChanged { claim.textFieldFocused = it.isFocused }
}

private const val PANEL_ID_LENGTH = 8
private const val RESIZE_STEP = 16
private val RESIZE_HANDLE = 8.dp
private val FOOTER_MAX_HEIGHT = 280.dp

/**
 * The card as a side panel of the board, [width] wide. [specReview] is null without a daemon; the owner's decision
 * shows in the footer only for a card in `SPEC_REVIEW`. [closure] is the closure screen's controller; without it
 * (no daemon socket) a Done card has no closing action. All text is drawn as plain text.
 */
@Composable
fun CardSidePanel(
    cardId: String,
    state: CardDetailState,
    tab: PanelTab,
    width: Dp,
    onTab: (PanelTab) -> Unit,
    onResize: (Int) -> Unit,
    onClose: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    specReview: SpecReviewController? = null,
    closure: ClosureController? = null,
) {
    val titleFocus = remember(cardId) { FocusRequester() }
    LaunchedEffect(cardId) { titleFocus.requestFocus() }
    val detail = (state as? CardDetailState.Loaded)?.detail
    val column = detail?.let { found -> BoardColumn.entries.firstOrNull { it.name == found.column } }
    if (state is CardDetailState.Loaded) LaunchedEffect(state.detail) { specReview?.onDetail(state.detail) }
    Row(
        Modifier
            .fillMaxHeight()
            .width(width)
            .shadow(24.dp)
            .background(Tokens.panel)
            .semantics { paneTitle = Texts.panelTitle(cardId.take(PANEL_ID_LENGTH)) },
    ) {
        ResizeHandle(width.value.roundToInt(), onResize)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PanelHeader(cardId, detail, column, titleFocus, onClose)
            if (detail != null) PanelTabs(tab, onTab)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    CardDetailState.Disconnected -> Muted(Texts.PANEL_DISCONNECTED)
                    CardDetailState.NotFound -> {
                        BasicText(Texts.cardNotFound(cardId), style = body(Tokens.text))
                        Muted(Texts.PANEL_NOT_FOUND_EXIT)
                    }
                    CardDetailState.Loading -> Muted(Texts.LOADING_CARD)
                    is CardDetailState.Loaded -> PanelBody(state.detail, tab, zone, closure)
                }
            }
            if (column != null && detail != null) {
                PanelFooterBar(column, detail, specReview.takeIf { specReviewActionsVisible(state) })
            }
        }
    }
}

/** The 8 px left edge: drag it, or press Left/Right with the focus on it, to change the width. */
@Composable
private fun ResizeHandle(widthPx: Int, onResize: (Int) -> Unit) {
    val density = LocalDensity.current.density
    val currentWidth by rememberUpdatedState(widthPx)
    val currentResize by rememberUpdatedState(onResize)
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(RESIZE_HANDLE)
            .fillMaxHeight()
            .semantics { contentDescription = Texts.RESIZE_PANEL }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> currentResize(currentWidth + RESIZE_STEP)
                    Key.DirectionRight -> currentResize(currentWidth - RESIZE_STEP)
                    else -> return@onKeyEvent false
                }
                true
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = currentWidth.toFloat() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged -= amount / density
                        currentResize(dragged.roundToInt())
                    },
                )
            },
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.edgeHi))
        if (focused) Box(Modifier.fillMaxSize().border(2.dp, Tokens.sand))
    }
}

@Composable
private fun PanelHeader(
    cardId: String,
    detail: CardDetail?,
    column: BoardColumn?,
    titleFocus: FocusRequester,
    onClose: () -> Unit,
) {
    val hue = column?.let { hueColor(stageHue(it)) } ?: Tokens.text
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "#${cardId.take(PANEL_ID_LENGTH)}",
                modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() },
                style = TextStyle(fontFamily = mono, fontSize = 13.sp, color = hue),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                detail?.total?.let {
                    BasicText(
                        formatCost(it.costUsd),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = tabular(TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.text)),
                    )
                }
                BasicText(
                    Texts.CLOSE_PANEL,
                    modifier = Modifier
                        .heightIn(min = 24.dp)
                        .border(1.dp, Tokens.edge, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = meta(Tokens.text),
                )
            }
        }
        detail?.let {
            BasicText(
                it.title,
                style = TextStyle(fontFamily = sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text),
            )
        }
        if (column != null) CardPathRail(column)
    }
}

/** Ten marks in board order: filled for the columns already traveled, ringed and larger for the current, hollow for the next. */
@Composable
private fun CardPathRail(column: BoardColumn) {
    val description = cardPathDescription(column)
    val hue = hueColor(stageHue(column))
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            cardPath(column).forEach { mark ->
                val base = Modifier.weight(1f)
                when (mark.step) {
                    PathStep.TRAVELED -> Box(base.height(6.dp).background(Tokens.muted, RoundedCornerShape(3.dp)))
                    PathStep.CURRENT -> Box(base.height(10.dp).background(hue, RoundedCornerShape(5.dp)).border(2.dp, Tokens.text, RoundedCornerShape(5.dp)))
                    PathStep.NEXT -> Box(base.height(6.dp).border(1.dp, Tokens.muted, RoundedCornerShape(3.dp)))
                }
            }
        }
        BasicText(description, style = meta(Tokens.soft))
    }
}

@Composable
private fun PanelTabs(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.width(IntrinsicSize.Max).selectable(selected = active, role = Role.Tab) { onSelect(tab) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    tabLabel(tab),
                    style = TextStyle(
                        fontFamily = sans,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) Tokens.text else Tokens.muted,
                    ),
                )
                Box(Modifier.height(2.dp).fillMaxWidth().background(if (active) Tokens.text else Color.Transparent))
            }
        }
    }
}

private fun tabLabel(tab: PanelTab): String =
    when (tab) {
        PanelTab.SUMMARY -> Texts.TAB_SUMMARY
        PanelTab.SPEC -> Texts.TAB_SPEC
        PanelTab.EVENTS -> Texts.TAB_EVENTS
        PanelTab.RUNS -> Texts.TAB_RUNS
    }

/** The fixed foot of the panel: the owner's decision for a card that waits for it, and nothing for any other card. */
@Composable
private fun PanelFooterBar(column: BoardColumn, detail: CardDetail, specReview: SpecReviewController?) {
    val footer = panelFooter(column) as? PanelFooter.Decision ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = FOOTER_MAX_HEIGHT)
            .border(1.dp, Tokens.edge)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(Texts.FOOTER_DECISION, style = meta(Tokens.sand).copy(fontWeight = FontWeight.SemiBold))
        BasicText(Texts.approveTo(footer.approveTo), style = meta(Tokens.soft))
        if (specReview != null) SpecReviewActions(detail.id, specReview) else if (column == BoardColumn.STAGED) Muted(Texts.FOOTER_STAGED_HINT)
    }
}

@Composable
private fun PanelBody(detail: CardDetail, tab: PanelTab, zone: ZoneId, closure: ClosureController?) {
    when (tab) {
        PanelTab.SUMMARY -> {
            if (closure != null && detail.column == DONE_COLUMN) ClosureEntry(detail, closure)
            openBlock(detail.events)?.let { BlockNotice(it, zone) }
            cardPullRequest(detail.events)?.let { PullRequestLine(it) }
            Section(Texts.GATES) {
                if (detail.gates.isEmpty()) Faint(Texts.NO_GATES)
                detail.gates.forEach { GateLineView(gateLine(it)) }
            }
        }
        PanelTab.SPEC -> {
            if (detail.criteria.isNotEmpty()) {
                Section(Texts.CRITERIA) { detail.criteria.forEach { CriterionLine(it) } }
            }
            Section(Texts.SPEC) {
                if (detail.spec.status == "missing" || detail.spec.text.isBlank()) {
                    Faint(Texts.NO_SPEC)
                } else {
                    SelectionContainer { BasicText(detail.spec.text, modifier = Modifier.widthIn(max = 640.dp), style = body()) }
                }
            }
        }
        PanelTab.EVENTS -> Section(Texts.TIMELINE) {
            BasicText(
                "${detail.events.size}",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.faint),
            )
            if (detail.eventsTruncated) Muted(Texts.EVENTS_TRUNCATED)
            if (detail.events.isEmpty()) Faint(Texts.NO_EVENTS) else detail.events.forEach { EventRow(it, zone) }
        }
        PanelTab.RUNS -> Section(Texts.RUNS) {
            if (detail.runs.isEmpty()) Faint(Texts.NO_RUNS)
            costByStage(detail.runs).forEach { StageCostGroup(it) }
            if (detail.runs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val bold = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text)
                    BasicText(if (detail.total.status == CostStatus.PARTIAL) "${Texts.TOTAL} (${Texts.PARTIAL_COST})" else Texts.TOTAL, style = bold)
                    BasicText(formatCost(detail.total.costUsd), style = tabular(bold))
                }
            }
        }
    }
}

/** The way into the closure screen; only a Done card has it. Opening it asks for the draft but sends nothing. */
@Composable
private fun ClosureEntry(detail: CardDetail, closure: ClosureController) {
    var open by remember(detail.id) { mutableStateOf(false) }
    if (open) {
        Section(Texts.CLOSURE_OPEN) { ClosureScreen(closure, detail.events, detail.id) { open = false } }
    } else {
        BasicText(
            Texts.CLOSURE_OPEN,
            modifier = Modifier
                .heightIn(min = 24.dp)
                .border(1.dp, Tokens.edgeHi, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = body(Tokens.text),
        )
    }
}

/** The owner's decision on a spec in `SPEC_REVIEW`; the column only changes when the daemon event arrives. */
@Composable
private fun SpecReviewActions(cardId: String, controller: SpecReviewController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var askedForChange by remember { mutableStateOf(false) }
    val busy = state.sending || state.sent
    val status = when {
        state.sending -> Texts.SENDING
        state.sent -> if (askedForChange) Texts.CHANGE_SENT else Texts.APPROVAL_SENT
        else -> ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.panel, RoundedCornerShape(16.dp))
            .border(1.dp, Tokens.edge, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(Texts.APPROVE, primary = true, enabled = !busy) {
                askedForChange = false
                scope.launch { controller.approve(cardId) }
            }
            ActionButton(Texts.REQUEST_CHANGE, primary = false, enabled = !busy && !state.notePending) {
                askedForChange = true
                scope.launch { controller.requestChange(cardId, null) }
            }
        }
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            if (status.isNotEmpty()) BasicText(status, style = body(Tokens.text))
        }
        if (state.notePending && !state.sent) {
            ChangeNote(
                enabled = !state.sending,
                onSend = { note -> scope.launch { controller.requestChange(cardId, note) } },
                onCancel = controller::cancel,
            )
        }
        state.notice?.let { NoticeLine(it, controller::dismissNotice) }
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        label,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 32.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (primary) Tokens.text else Color.Transparent, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Tokens.sand else if (primary) Tokens.text else Tokens.edgeHi, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = TextStyle(
            fontFamily = sans,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Tokens.ground else Tokens.text,
        ),
    )
}

/** The mandatory note of a change request; Enter sends, Shift+Enter breaks the line, Esc cancels. */
@Composable
private fun ChangeNote(enabled: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(Texts.NOTE_LABEL, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.soft))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            cursorBrush = SolidColor(Tokens.text),
            textStyle = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .heightIn(min = 64.dp)
                .border(1.dp, Tokens.edgeHi, RoundedCornerShape(6.dp))
                .padding(8.dp)
                .claimsEscape()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> onCancel()
                        event.key == Key.Enter && !event.isShiftPressed -> onSend(text)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
        if (text.isBlank()) BasicText(Texts.NOTE_REQUIRED, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(Texts.SEND_NOTE, primary = true, enabled = enabled) { onSend(text) }
            ActionButton(Texts.CANCEL, primary = false, enabled = enabled, onClick = onCancel)
        }
    }
}

/** A refusal or a "not sent": a textual label besides the color, and a button to dismiss it. */
@Composable
private fun NoticeLine(notice: MoveNotice, onDismiss: () -> Unit) {
    val label = if (notice.kind == NoticeKind.REFUSED) Texts.NOTICE_REFUSED else Texts.NOTICE_NOT_SENT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .border(1.dp, Tokens.bad, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(label, style = body(Tokens.bad).copy(fontWeight = FontWeight.SemiBold))
        BasicText(notice.text, style = body(Tokens.text))
        Row { ActionButton(Texts.DISMISS_NOTICE, primary = false, enabled = true, onClick = onDismiss) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.panel, RoundedCornerShape(16.dp))
            .border(1.dp, Tokens.edge, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            title,
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text),
        )
        content()
    }
}

/** The blocked notice comes first in Summary because it is what asks for action. */
@Composable
private fun BlockNotice(block: OpenBlock, zone: ZoneId) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Tokens.bad, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(Texts.BLOCKED, style = body(Tokens.bad).copy(fontWeight = FontWeight.SemiBold))
            BasicText(formatInstant(block.at, zone), style = tabular(meta()))
        }
        BasicText(block.reason, style = body(Tokens.text))
    }
}

@Composable
private fun PullRequestLine(pr: PullRequestView) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(Texts.pullRequest(pr.number), style = body(Tokens.text).copy(fontWeight = FontWeight.SemiBold))
        BasicText(if (pr.state == PrState.MERGED) Texts.PR_MERGED else Texts.PR_OPEN, style = body())
    }
}

@Composable
private fun GateLineView(line: GateLine) {
    val passed = line.verdict == PASS
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(line.verdict, style = meta(if (passed) Tokens.ok else Tokens.bad).copy(fontWeight = FontWeight.SemiBold))
            BasicText(line.name, style = TextStyle(fontFamily = mono, fontSize = 13.sp, color = Tokens.text))
            BasicText(line.duration, style = tabular(meta()))
        }
        line.reason?.let { BasicText(it, style = body()) }
    }
}

@Composable
private fun CriterionLine(criterion: CriterionRow) {
    val color = when (criterion.gate) {
        PASS -> Tokens.ok
        FAIL -> Tokens.bad
        NO_GATE -> Tokens.faint
        else -> Tokens.muted
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(criterion.ac, style = meta(Tokens.text).copy(fontWeight = FontWeight.SemiBold))
        BasicText(criterion.layer, style = meta(Tokens.soft))
        BasicText(criterion.testFile, style = TextStyle(fontFamily = mono, fontSize = 13.sp, color = Tokens.soft))
        BasicText(criterionResult(criterion), style = meta(color))
    }
}

@Composable
private fun EventRow(event: TimelineEvent, zone: ZoneId) {
    val owner = event.isOwnerDecision()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(formatInstant(event.at, zone), style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = Tokens.faint))
        // Filled mark for the owner's signed decision, hollow for agents: the state does not rely on color alone.
        val mark = Modifier.size(8.dp)
        Box(if (owner) mark.background(Tokens.text) else mark.border(1.dp, Tokens.muted))
        BasicText(event.actor, style = body(Tokens.text).copy(fontWeight = FontWeight.SemiBold))
        BasicText(event.type, style = body())
        if (owner) {
            BasicText(
                Texts.SIGNED_BY_OWNER,
                modifier = Modifier.border(1.dp, Tokens.edge, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.text),
            )
        }
    }
}

@Composable
private fun StageCostGroup(group: StageRunCost) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val head = body(Tokens.text).copy(fontWeight = FontWeight.SemiBold)
            BasicText(group.stage, style = head)
            val partial = if (group.status == CostStatus.PARTIAL) " (${Texts.PARTIAL_COST})" else ""
            BasicText(formatCost(group.costUsd) + partial, style = tabular(head))
        }
        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            group.runs.forEach { RunLine(it) }
        }
    }
}

@Composable
private fun RunLine(run: RunRow) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(run.runtime, style = body())
            BasicText(formatCost(run.costUsd), style = tabular(body(Tokens.text)))
        }
        run.reason?.let { BasicText(it, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.muted)) }
    }
}

@Composable
private fun Muted(text: String) = BasicText(text, style = body(Tokens.muted))

@Composable
private fun Faint(text: String) = BasicText(text, style = body(Tokens.faint))
