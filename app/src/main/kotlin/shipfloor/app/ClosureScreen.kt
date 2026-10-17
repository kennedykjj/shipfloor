package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import kotlinx.coroutines.launch

/** The owner's last step on a Done card: reads the draft the daemon will post, and confirms it. The screen is the confirmation. */
@Composable
fun ClosureScreen(controller: ClosureController, events: List<TimelineEvent>, cardId: String, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, cardId) { controller.open(cardId, events) }
    val confirm: () -> Unit = { scope.launch { controller.confirm() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onKeyEvent {
                val escape = it.type == KeyEventType.KeyUp && it.key == Key.Escape
                if (escape) onBack()
                escape
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            Texts.CLOSURE_TITLE,
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text),
        )
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val current = state) {
                ClosureState.Loading -> Line(Texts.CLOSURE_LOADING, Tokens.muted)
                is ClosureState.Draft -> {
                    DraftBody(current.status, current.summary, current.prLink)
                    current.notSent?.let { Line(Texts.closureNotSent(it), Tokens.bad) }
                }
                is ClosureState.Refused -> Line(Texts.closureRefused(current.reason), Tokens.bad)
                is ClosureState.Confirming -> Line(Texts.CLOSURE_CONFIRMING, Tokens.muted)
                ClosureState.Closed -> Line(Texts.CLOSURE_CLOSED, Tokens.ok)
                is ClosureState.Failed -> {
                    Line(Texts.closureFailed(current.reason), Tokens.bad)
                    current.notSent?.let { Line(Texts.closureNotSent(it), Tokens.bad) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is ClosureState.Draft -> AppButton(Texts.CLOSURE_CONFIRM, AppIcon.CHECK, ButtonKind.PRIMARY, onClick = confirm)
                is ClosureState.Failed -> AppButton(Texts.RETRY, AppIcon.REFRESH, ButtonKind.PRIMARY, onClick = confirm)
                else -> Unit
            }
            AppButton(Texts.BACK, AppIcon.ARROW_LEFT, onClick = onBack)
        }
    }
}

@Composable
private fun DraftBody(status: String, summary: String, prLink: String) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(Texts.CLOSURE_STATUS, status)
            Field(Texts.CLOSURE_SUMMARY, summary)
            Field(Texts.CLOSURE_PR, prLink)
        }
    }
    BasicText(Texts.CLOSURE_NOTICE, style = TextStyle(fontFamily = sans, fontSize = 13.sp, color = Tokens.muted))
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = TextStyle(fontFamily = sans, fontSize = 12.sp, color = Tokens.faint))
        BasicText(value, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft))
    }
}

@Composable
private fun Line(text: String, color: Color) {
    BasicText(text, style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = color))
}

