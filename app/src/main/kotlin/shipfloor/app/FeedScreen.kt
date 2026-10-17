package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private const val NARROW_DP = 560

@Composable
fun FeedScreen(
    state: FeedState,
    today: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    onSelectStage: (String?) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Tokens.ground)) {
        val compact = maxWidth < NARROW_DP.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, today, onSelectDay)
            CostPanel(state.cost, compact)
            StageChips(state, onSelectStage)
            Body(state, compact)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageChips(state: FeedState, onSelect: (String?) -> Unit) {
    if (state.stages.size <= 1) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.stages.forEach { chip ->
            val label = "${chip.stage?.replaceFirstChar { it.uppercase() } ?: Texts.ALL_STAGES} ${chip.count}"
            StageChipButton(label, selected = chip.stage == state.stage) { onSelect(chip.stage) }
        }
    }
}

/** A chip of at least 32dp; the selected one changes border and weight, not only color. */
@Composable
private fun StageChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    BasicText(
        label,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Tokens.sand else Color.Transparent, shape)
            .border(1.dp, if (selected) Tokens.edgeHi else Tokens.edge, shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = TextStyle(
            fontFamily = sans,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Tokens.text else Tokens.soft,
        ),
    )
}

@Composable
private fun Header(state: FeedState, today: LocalDate, onSelectDay: (LocalDate) -> Unit) {
    val isToday = state.day >= today
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(Texts.FEED_TITLE, style = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
        if (state.connection == FeedConnection.Reconnecting) {
            BasicText(
                Texts.RECONNECTING,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.warn),
            )
        }
        Box(Modifier.weight(1f))
        IconButton(AppIcon.CHEVRON_LEFT, Texts.PREVIOUS_DAY) { onSelectDay(state.day.minusDays(1)) }
        BasicText(
            if (isToday) Texts.TODAY else DAY_FORMAT.format(state.day),
            style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
        )
        IconButton(AppIcon.CHEVRON_RIGHT, if (isToday) Texts.CURRENT_DAY else Texts.NEXT_DAY, enabled = !isToday) { onSelectDay(state.day.plusDays(1)) }
        if (!isToday) AppButton(Texts.TODAY, AppIcon.CALENDAR) { onSelectDay(today) }
    }
}

@Composable
private fun Body(state: FeedState, compact: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.earlierHistoryUnavailable) {
            BasicText(Texts.EARLIER_HISTORY_UNAVAILABLE, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
        }
        when {
            state.loading -> Note(Texts.LOADING)
            state.failed -> Note(Texts.FEED_ERROR)
            state.rows.isEmpty() -> Note(if (state.stage != null) Texts.EMPTY_STAGE else Texts.EMPTY_DAY)
            else -> EventList(state.rows, compact)
        }
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft))
}

@Composable
private fun EventList(rows: List<FeedRow>, compact: Boolean) {
    val listState = rememberLazyListState()
    val atEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= rows.lastIndex } ?: true } }
    var seen by remember { mutableStateOf(rows.size) }
    if (atEnd) seen = rows.size
    LaunchedEffect(rows.size, atEnd) {
        if (atEnd && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    val unseen = rows.size - seen
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.seq }) { EventRow(it, compact) }
        }
        if (unseen > 0) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                AppButton(Texts.newEvents(unseen), AppIcon.ARROW_DOWN, ButtonKind.PRIMARY) { seen = rows.size }
            }
        }
    }
    LaunchedEffect(seen) {
        if (seen == rows.size && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
}

@Composable
private fun EventRow(row: FeedRow, compact: Boolean) {
    val actorText = listOfNotNull(row.actorKind, row.actorDetail).joinToString(" · ")
    if (compact) {
        Column(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EventHead(row)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActorLabel(actorText)
                CostLabel(row)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EventHead(row)
            ActorLabel(actorText)
            Box(Modifier.weight(1f))
            CostLabel(row)
        }
    }
}

@Composable
private fun EventHead(row: FeedRow) {
    BasicText(row.time, style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = Tokens.muted))
    Mark(row.outcome)
    BasicText(row.type, style = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text))
    BasicText(row.card, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft))
}

@Composable
private fun ActorLabel(text: String) {
    BasicText(text, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
}

/** The event's own estimate (mono, tabular); a partial or unknown cost also carries its tag and a screen-reader text. */
@Composable
private fun CostLabel(row: FeedRow) {
    val cost = row.cost ?: return
    val tag = when (row.costTag) {
        CostTag.Partial -> Texts.PARTIAL
        CostTag.Unknown -> Texts.UNKNOWN
        null -> null
    }
    Row(
        modifier = Modifier.semantics { contentDescription = listOfNotNull(cost, tag).joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            cost,
            style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = Tokens.muted, fontFeatureSettings = "tnum", textAlign = TextAlign.End),
        )
        if (tag != null) BasicText(tag, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.warn))
    }
}

/** Dot for ok, diamond for failure, dash for a row without outcome: shape and color together. */
@Composable
private fun Mark(outcome: FeedOutcome) {
    when (outcome) {
        FeedOutcome.Ok -> Box(Modifier.size(8.dp).background(Tokens.ok, CircleShape))
        FeedOutcome.Failed ->
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer(rotationZ = 45f)
                    .background(Tokens.bad)
                    .semantics { contentDescription = Texts.FAILED },
            )
        FeedOutcome.None -> Box(Modifier.size(width = 8.dp, height = 2.dp).background(Tokens.faint))
    }
}
