package shipfloor.spike

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.SystemTray
import java.awt.Toolkit
import kotlinx.coroutines.delay

private val Sand = Color(0xFFC7B9A6)
private val Ground = Color(0xF00A0A0A)
private val Text = Color(0xFFF2F1EE)
private val Muted = Color(0xFFA3ABB6)

private fun log(message: String) = println("[spike] $message")

private object Dot : Painter() {
    override val intrinsicSize = Size(32f, 32f)
    override fun DrawScope.onDraw() {
        drawCircle(Sand)
    }
}

fun main() {
    val seconds = System.getProperty("spike.seconds")?.toIntOrNull() ?: 0
    val transparent = System.getProperty("spike.transparent") != "false"
    log(
        "toolkit=${Toolkit.getDefaultToolkit().javaClass.simpleName} " +
            "java=${System.getProperty("java.vendor")} ${System.getProperty("java.version")} " +
            "awtTraySupported=${SystemTray.isSupported()} " +
            "session=${System.getenv("XDG_SESSION_TYPE")}",
    )
    application {
        var popup by remember { mutableStateOf(false) }
        val tray = rememberTrayState()

        if (seconds > 0) {
            LaunchedEffect(Unit) {
                delay(seconds * 1000L)
                log("closing after $seconds s")
                exitApplication()
            }
        }

        Tray(
            icon = Dot,
            state = tray,
            tooltip = "shipfloor spike",
            onAction = { popup = true },
            menu = {
                Item("Mostrar pop-up", onClick = { popup = true })
                Item("Enviar notificação", onClick = {
                    tray.sendNotification(Notification("shipfloor", "O card #42 espera você em Spec Review"))
                    log("notification sent")
                })
                Item("Sair", onClick = ::exitApplication)
            },
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "shipfloor spike",
            undecorated = true,
            transparent = transparent,
            alwaysOnTop = true,
            resizable = false,
            state = rememberWindowState(
                size = DpSize(280.dp, 150.dp),
                position = WindowPosition(Alignment.BottomEnd),
            ),
        ) {
            LaunchedEffect(Unit) {
                delay(1500)
                log("overlay renderApi=${window.renderApi} transparent=$transparent alwaysOnTop=${window.isAlwaysOnTop} location=${window.location} size=${window.size}")
            }
            Overlay(onPopup = { popup = true })
        }

        if (popup) {
            Window(
                onCloseRequest = { popup = false },
                title = "shipfloor: pop-up",
                alwaysOnTop = true,
                state = rememberWindowState(size = DpSize(360.dp, 170.dp), position = WindowPosition(Alignment.Center)),
            ) {
                LaunchedEffect(Unit) { log("popup shown") }
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF14171B)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Card #42 espera você", color = Text, fontSize = 18.sp)
                    Text("Spec Review · shipfloor", color = Muted, fontSize = 13.sp)
                    Button(onClick = { popup = false }) { Text("Fechar") }
                }
            }
        }
    }
}

@Composable
private fun Overlay(onPopup: () -> Unit) {
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                frames++
                if (now - start >= 1_000_000_000L) {
                    fps = frames
                    log("fps=$frames")
                    frames = 0
                    start = now
                }
            }
        }
    }
    val angle by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
    )
    Box(Modifier.fillMaxSize().background(Ground, RoundedCornerShape(16.dp)).clickable(onClick = onPopup).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(56.dp)) {
                rotate(angle) {
                    drawRect(Sand, topLeft = Offset(size.width * .2f, size.height * .2f), size = size * .6f)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("shipfloor · sempre no topo", color = Text, fontSize = 14.sp)
                Text("$fps fps", color = Sand, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                Text("clique para o pop-up", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

