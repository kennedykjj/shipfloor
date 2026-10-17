package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shipfloor.core.CostSetting
import shipfloor.core.FactorySettings
import shipfloor.core.NotificationType
import shipfloor.core.WorkHoursSetting

private const val SAVED_NOTICE_MILLIS = 5_000L
private val TIME_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DEFAULT_HOURS = WorkHoursSetting(
    days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    start = LocalTime.of(9, 0),
    end = LocalTime.of(18, 0),
    zone = ZoneId.systemDefault().id,
)

/** What the screen shows: reading, unreadable, or the editable draft. */
private sealed interface Loaded {
    data object Reading : Loaded

    data object Failed : Loaded

    data class Ready(val draft: SettingsDraft) : Loaded
}

@Composable
fun SettingsScreen(client: SettingsClient, onBack: () -> Unit) {
    var loaded by remember { mutableStateOf<Loaded>(Loaded.Reading) }
    var saving by remember { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    // Text the owner typed that is not a valid value yet: field name to the message shown under it.
    var badInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    fun load() {
        loaded = Loaded.Reading
        scope.launch {
            val result = withContext(Dispatchers.IO) { client.load() }
            loaded = when (result) {
                is LoadResult.Loaded -> Loaded.Ready(SettingsDraft(result.settings))
                LoadResult.Unreachable -> Loaded.Failed
            }
        }
    }

    fun save(current: SettingsDraft) {
        if (badInputs.isNotEmpty()) return
        saving = true
        justSaved = false
        scope.launch {
            val next = withContext(Dispatchers.IO) { current.save(client) }
            saving = false
            loaded = Loaded.Ready(next)
            if (next.error == null && next.issues.isEmpty()) {
                justSaved = true
                delay(SAVED_NOTICE_MILLIS)
                justSaved = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(Tokens.ground)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(Texts.BACK, enabled = !saving, onClick = onBack)
            Label(Texts.SETTINGS_TITLE, 17, FontWeight.SemiBold, Tokens.text)
        }
        when (val state = loaded) {
            Loaded.Reading -> Box(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Label(Texts.SETTINGS_LOADING, 14, FontWeight.Normal, Tokens.muted)
            }
            Loaded.Failed -> Column(Modifier.padding(16.dp)) { Banner(Texts.UNREACHABLE_LOAD, onRetry = ::load) }
            is Loaded.Ready -> Editor(
                state = state.draft,
                saving = saving,
                justSaved = justSaved,
                badInputs = badInputs,
                onBadInputs = { badInputs = it },
                onChange = { loaded = Loaded.Ready(state.draft.copy(draft = it, error = null, issues = emptyList())) },
                onDiscard = {
                    badInputs = emptyMap()
                    loaded = Loaded.Ready(state.draft.discard())
                },
                onSave = { save(state.draft) },
            )
        }
    }
}

@Composable
private fun Editor(
    state: SettingsDraft,
    saving: Boolean,
    justSaved: Boolean,
    badInputs: Map<String, String>,
    onBadInputs: (Map<String, String>) -> Unit,
    onChange: (FactorySettings) -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val draft = state.draft
    val enabled = !saving
    val issues = state.issues.associate { it.field to Texts.issueReason(it.field, it.reason) }
    val problems = (badInputs.keys + issues.keys).size

    fun input(field: String, message: String?): Map<String, String> =
        if (message == null) badInputs - field else badInputs + (field to message)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Group(Texts.GROUP_POOL) {
                    SettingRow(
                        label = Texts.POOL_LABEL,
                        description = Texts.POOL_DESCRIPTION,
                        error = issues["poolSize"],
                        changed = draft.poolSize != state.saved.poolSize,
                    ) {
                        Stepper(draft.poolSize, enabled) { onChange(draft.copy(poolSize = it)) }
                    }
                }
                Group(Texts.GROUP_HOURS) {
                    val hours = draft.workHours
                    SettingRow(
                        label = Texts.HOURS_LABEL,
                        description = Texts.HOURS_DESCRIPTION,
                        error = issues["workHours"] ?: issues["workHours.zone"],
                        changed = hours != state.saved.workHours,
                    ) {
                        Switch(hours != null, enabled) { onChange(draft.copy(workHours = if (it) DEFAULT_HOURS else null)) }
                    }
                    SettingRow(label = Texts.HOURS_START, description = null, error = badInputs["workHours.start"], changed = false) {
                        TimeField(hours?.start, enabled && hours != null, "workHours.start", { onBadInputs(input("workHours.start", it)) }) { t ->
                            if (hours != null && t != null) onChange(draft.copy(workHours = hours.copy(start = t)))
                        }
                    }
                    SettingRow(label = Texts.HOURS_END, description = null, error = badInputs["workHours.end"], changed = false) {
                        TimeField(hours?.end, enabled && hours != null, "workHours.end", { onBadInputs(input("workHours.end", it)) }) { t ->
                            if (hours != null && t != null) onChange(draft.copy(workHours = hours.copy(end = t)))
                        }
                    }
                    SettingRow(label = Texts.GROUP_HOURS, description = null, error = issues["workHours.days"], changed = false) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEachIndexed { index, day ->
                                val on = hours?.days?.contains(day) == true
                                Chip(Texts.DAY_NAMES[index], on, enabled && hours != null) {
                                    hours?.let { onChange(draft.copy(workHours = it.copy(days = if (on) it.days - day else it.days + day))) }
                                }
                            }
                        }
                    }
                }
                Group(Texts.GROUP_COST) {
                    SettingRow(
                        label = Texts.COST_CARD,
                        description = Texts.COST_DESCRIPTION,
                        error = badInputs["cost.perCardUsd"] ?: issues["cost.perCardUsd"],
                        changed = draft.cost.perCardUsd != state.saved.cost.perCardUsd,
                    ) {
                        AmountField(draft.cost.perCardUsd, enabled, { onBadInputs(input("cost.perCardUsd", it)) }) {
                            onChange(draft.copy(cost = draft.cost.copy(perCardUsd = it)))
                        }
                    }
                    SettingRow(
                        label = Texts.COST_DAY,
                        description = Texts.COST_DESCRIPTION,
                        error = badInputs["cost.perDayUsd"] ?: issues["cost.perDayUsd"],
                        changed = draft.cost.perDayUsd != state.saved.cost.perDayUsd,
                    ) {
                        AmountField(draft.cost.perDayUsd, enabled, { onBadInputs(input("cost.perDayUsd", it)) }) {
                            onChange(draft.copy(cost = draft.cost.copy(perDayUsd = it)))
                        }
                    }
                }
                Group(Texts.GROUP_NOTIFICATIONS) {
                    Label(Texts.NOTIFICATIONS_NOTE, 13, FontWeight.Normal, Tokens.muted)
                    NotificationType.entries.forEach { type ->
                        val on = type in draft.notifications.enabled
                        SettingRow(
                            label = Texts.notificationLabel(type),
                            description = null,
                            error = null,
                            changed = on != (type in state.saved.notifications.enabled),
                        ) {
                            Switch(on, enabled) {
                                val next = if (it) draft.notifications.enabled + type else draft.notifications.enabled - type
                                onChange(draft.copy(notifications = draft.notifications.copy(enabled = next)))
                            }
                        }
                    }
                    SettingRow(
                        label = Texts.QUIET_LABEL,
                        description = Texts.QUIET_DESCRIPTION,
                        error = badInputs["notifications.quietStart"] ?: badInputs["notifications.quietEnd"],
                        changed = draft.notifications.quietStart != state.saved.notifications.quietStart ||
                            draft.notifications.quietEnd != state.saved.notifications.quietEnd,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimeField(draft.notifications.quietStart, enabled, "notifications.quietStart", { onBadInputs(input("notifications.quietStart", it)) }, optional = true) {
                                onChange(draft.copy(notifications = draft.notifications.copy(quietStart = it)))
                            }
                            TimeField(draft.notifications.quietEnd, enabled, "notifications.quietEnd", { onBadInputs(input("notifications.quietEnd", it)) }, optional = true) {
                                onChange(draft.copy(notifications = draft.notifications.copy(quietEnd = it)))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (justSaved) Label(Texts.SETTINGS_SAVED, 14, FontWeight.Normal, Tokens.ok)
            when (state.error) {
                SaveError.UNREACHABLE -> Banner(Texts.UNREACHABLE, onRetry = onSave)
                SaveError.REFUSED -> Banner(Texts.REFUSED, onRetry = null)
                null -> Unit
            }
            if (state.changeCount > 0 || problems > 0) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Tokens.panel, RoundedCornerShape(12.dp))
                        .border(1.dp, Tokens.edge, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        when {
                            saving -> Label(Texts.SETTINGS_SAVING, 14, FontWeight.Normal, Tokens.muted)
                            problems > 0 -> Label(Texts.fixFields(problems), 14, FontWeight.Normal, Tokens.bad)
                            else -> Label(Texts.changes(state.changeCount), 14, FontWeight.Normal, Tokens.text)
                        }
                    }
                    GhostButton(Texts.DISCARD, enabled, onDiscard)
                    FilledButton(Texts.SAVE, enabled && badInputs.isEmpty(), onSave)
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Tokens.panel, RoundedCornerShape(16.dp))
            .border(1.dp, Tokens.edge, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(title, 17, FontWeight.SemiBold, Tokens.text)
        content()
    }
}

/** A setting row: label and description on the left, the control on the right, the field error below. */
@Composable
private fun SettingRow(label: String, description: String?, error: String?, changed: Boolean, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f).widthIn(max = 448.dp)) {
                Label(label, 14, FontWeight.Normal, if (changed) Tokens.text else Tokens.soft)
                description?.let { Label(it, 13, FontWeight.Normal, Tokens.muted) }
            }
            control()
        }
        error?.let { Label("${label}: $it", 12, FontWeight.Normal, Tokens.bad) }
    }
}

@Composable
private fun Label(text: String, size: Int, weight: FontWeight, color: Color) {
    BasicText(text, style = TextStyle(fontFamily = sans, fontSize = size.sp, fontWeight = weight, color = color))
}

@Composable
private fun Stepper(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("−", enabled && value > 1) { onChange(value - 1) }
        BasicText(value.toString(), style = TextStyle(fontFamily = mono, fontSize = 14.sp, color = Tokens.text))
        GhostButton("+", enabled) { onChange(value + 1) }
    }
}

@Composable
private fun Switch(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }.heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(if (on) Texts.ON else Texts.OFF, 13, FontWeight.Normal, if (enabled) Tokens.soft else Tokens.faint)
        Box(
            Modifier.widthIn(min = 36.dp).heightIn(min = 20.dp)
                .background(if (on) Tokens.text else Tokens.panel, RoundedCornerShape(10.dp))
                .border(1.dp, Tokens.edge, RoundedCornerShape(10.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.widthIn(min = 12.dp).heightIn(min = 12.dp).background(if (on) Tokens.ground else Tokens.muted, RoundedCornerShape(6.dp)))
        }
    }
}

/** A day toggle; the state is also in the text (a check mark), not only in the colour. */
@Composable
private fun Chip(text: String, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.heightIn(min = 24.dp)
            .background(if (on) Tokens.edge else Tokens.panel, RoundedCornerShape(8.dp))
            .border(if (on) 2.dp else 1.dp, if (on) Tokens.text else Tokens.edge, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(if (on) "✓ $text" else text, 13, FontWeight.Normal, if (enabled) Tokens.text else Tokens.faint)
    }
}

@Composable
private fun GhostButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 24.dp)
            .border(1.dp, Tokens.edge, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, 14, FontWeight.Normal, if (enabled) Tokens.text else Tokens.faint)
    }
}

@Composable
private fun FilledButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 24.dp)
            .background(if (enabled) Tokens.text else Tokens.edge, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, 14, FontWeight.SemiBold, if (enabled) Tokens.ground else Tokens.faint)
    }
}

@Composable
private fun Banner(text: String, onRetry: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, Tokens.bad, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) { Label(text, 14, FontWeight.Normal, Tokens.bad) }
        onRetry?.let { GhostButton(Texts.RETRY, true, it) }
    }
}

/** A boxed text field. [onValid] gets each parsed value; [onInvalid] gets the message, or null once the text is valid. */
@Composable
private fun Field(text: String, enabled: Boolean, family: FontFamily, prefix: String?, suffix: String?, onText: (String) -> Unit) {
    Row(
        Modifier.border(1.dp, Tokens.edge, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp).widthIn(min = 80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        prefix?.let { Label(it, 13, FontWeight.Normal, Tokens.muted) }
        BasicTextField(
            value = text,
            onValueChange = onText,
            enabled = enabled,
            singleLine = true,
            cursorBrush = SolidColor(Tokens.sand),
            textStyle = TextStyle(fontFamily = family, fontSize = 14.sp, color = if (enabled) Tokens.text else Tokens.faint),
            modifier = Modifier.widthIn(min = 56.dp),
        )
        suffix?.let { Label(it, 13, FontWeight.Normal, Tokens.muted) }
    }
}

@Composable
private fun TimeField(
    value: LocalTime?,
    enabled: Boolean,
    field: String,
    onInvalid: (String?) -> Unit,
    optional: Boolean = false,
    onValid: (LocalTime?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.format(TIME_TEXT).orEmpty()) }
    Field(text, enabled, mono, null, null) { typed ->
        text = typed
        when {
            typed.isBlank() && optional -> {
                onInvalid(null)
                onValid(null)
            }
            else -> try {
                val parsed = LocalTime.parse(typed.trim().padStart(5, '0'), TIME_TEXT)
                onInvalid(null)
                onValid(parsed)
            } catch (e: DateTimeParseException) {
                onInvalid(Texts.BAD_TIME)
            }
        }
    }
}

@Composable
private fun AmountField(value: BigDecimal?, enabled: Boolean, onInvalid: (String?) -> Unit, onValid: (BigDecimal?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toPlainString().orEmpty()) }
    Field(text, enabled, mono, Texts.COST_PREFIX, Texts.COST_SUFFIX) { typed ->
        text = typed
        if (typed.isBlank()) {
            onInvalid(null)
            onValid(null)
        } else {
            val parsed = typed.trim().toBigDecimalOrNull()
            if (parsed == null) {
                onInvalid(Texts.BAD_AMOUNT)
            } else {
                onInvalid(null)
                onValid(parsed)
            }
        }
    }
}
