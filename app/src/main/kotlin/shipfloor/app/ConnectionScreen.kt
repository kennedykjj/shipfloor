package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectionScreen(state: ConnectionState, onOpenSettings: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize().background(Tokens.ground).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .background(Tokens.panel, RoundedCornerShape(16.dp))
                .border(1.dp, Tokens.edge, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                Texts.TITLE,
                style = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tokens.text),
            )
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).background(dotColor(state), CircleShape))
                BasicText(headline(state), style = TextStyle(fontFamily = sans, fontSize = 15.sp, color = Tokens.text))
            }
            if (state is ConnectionState.Disconnected) Explanation(Texts.explain(state.reason))
            if (state == ConnectionState.Connected && onOpenSettings != null) {
                SettingsButton(onOpenSettings)
            }
        }
    }
}

@Composable
internal fun SettingsButton(onClick: () -> Unit) {
    BasicText(
        Texts.OPEN_SETTINGS,
        modifier = Modifier
            .border(1.dp, Tokens.edge, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.text),
    )
}

@Composable
private fun Explanation(explanation: Texts.Explanation) {
    val small = TextStyle(fontFamily = sans, fontSize = 14.sp, color = Tokens.soft)
    BasicText(explanation.reason, style = small)
    BasicText(explanation.step, style = small)
    explanation.command?.let { command ->
        SelectionContainer {
            BasicText(
                command,
                modifier = Modifier.background(Tokens.ground, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = Tokens.text),
            )
        }
    }
}

private fun headline(state: ConnectionState): String =
    when (state) {
        ConnectionState.Connecting -> Texts.CONNECTING
        ConnectionState.Connected -> Texts.CONNECTED
        is ConnectionState.Disconnected -> Texts.DISCONNECTED
    }

private fun dotColor(state: ConnectionState): Color =
    when (state) {
        ConnectionState.Connecting -> Tokens.muted
        ConnectionState.Connected -> Tokens.ok
        is ConnectionState.Disconnected -> Tokens.warn
    }
