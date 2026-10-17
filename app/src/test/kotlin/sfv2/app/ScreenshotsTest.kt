package sfv2.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import sfv2.core.Column as BoardColumn

/** Sample data for screens: a factory mid-day with something running and two decisions waiting. */
object ScreenFixtures {
    private fun cardIn(column: BoardColumn, id: String, title: String, minutes: Long, cost: String? = null) = BoardCard(
        id = id,
        title = title,
        column = column,
        enteredAt = Instant.now().minus(Duration.ofMinutes(minutes)),
        cost = cost?.let { BoardCost(CostStatus.KNOWN, BigDecimal(it)) },
    )

    fun board(): Board = Board(
        cards = listOf(
            cardIn(BoardColumn.INBOX, "ac51d58a-0000", "Virada: a sfv2 assume o próprio repositório", 655),
            cardIn(BoardColumn.INBOX, "cf23240a-0000", "Decisão sobre o cartum", 655),
            cardIn(BoardColumn.INBOX, "a1e8d774-0000", "Serviço no macOS e no Windows", 655),
            cardIn(BoardColumn.INBOX, "0e3864ff-0000", "CI e pacotes para macOS e Windows", 655),
            cardIn(BoardColumn.READY_FOR_REFINEMENT, "3be90c12-0000", "Exportar eventos do dia em CSV", 25),
            cardIn(BoardColumn.REFINEMENT, "91d2e7a0-0000", "Filtro por projeto no Feed do dia", 12, "0.18"),
            cardIn(BoardColumn.SPEC_REVIEW, "7c113339-0000", "Atualizador automático", 283, "0.21"),
            cardIn(BoardColumn.READY_FOR_DEV, "5a77b3e1-0000", "Atalho Ctrl N para criar card", 130),
            cardIn(BoardColumn.CODING, "e04c68d2-0000", "Notificação nativa de decisão pendente", 37, "1.84"),
            cardIn(BoardColumn.VALIDATION, "28fb1c90-0000", "Histórico na paleta de comandos", 9, "3.02"),
            cardIn(BoardColumn.STAGED, "b6a40f55-0000", "Barra Onde o tempo vai: previsão", 72, "4.70"),
        ) + (1..12).map { cardIn(BoardColumn.DONE, "d%07x-0000".format(it), "Card concluído número $it", 120L + it, "0.40") },
        lastSeq = 1,
    )

    fun view(): BoardView = BoardView(board(), live = true)

    fun detail(): CardDetail = CardDetail(
        id = "7c113339-0000",
        title = "Atualizador automático",
        column = "SPEC_REVIEW",
        spec = SpecView(
            "ready",
            "## Problema\n\nO app não sabe quando há versão nova do daemon.\n\n## Critérios de aceitação\n\n" +
                "1. Dado que há versão nova, quando o app abre, então avisa o dono.\n" +
                "2. Dado que o dono recusa, então o aviso não volta no mesmo dia.",
        ),
        events = listOf(
            TimelineEvent("e1", Instant.now().minus(Duration.ofMinutes(283)), "agent:po", "SPEC_WRITTEN", mapOf("section" to "product")),
            TimelineEvent("e2", Instant.now().minus(Duration.ofMinutes(260)), "gate:test-plan", "GATE_PASSED", mapOf("gate" to "test-plan")),
        ),
        eventsTruncated = false,
        runs = listOf(RunRow("r1", "REFINE", "claude", CostStatus.KNOWN, BigDecimal("0.21"), null)),
        total = CostTotal(CostStatus.KNOWN, BigDecimal("0.21")),
        gates = listOf(GateRow("test-plan", "PASS", null, 1200, Instant.now().minus(Duration.ofMinutes(260)))),
        criteria = listOf(CriterionRow("AC-1", "app", "AppTest.kt", "PASS"), CriterionRow("AC-2", "app", "AppTest.kt", "none")),
    )

    fun pulse(): PulseView = PulseView(
        freshness = PulseFreshness.Live,
        stateText = "Trabalhando",
        phrase = "Três cards em andamento. Dois esperam a sua decisão.",
        sessionsText = "3 de 4 sessões",
        squares = listOf(true, true, true, false),
        activeCards = listOf("91d2e7a0", "e04c68d2", "28fb1c90"),
        ceiling = PulseCeiling.Meter("est. US$ 9,74", 3, LimitState.Within),
        notice = null,
        forecast = ForecastCell("Fila esvazia em ~2 h 40", "baseline", "Previsão"),
    )
}

/** Renders screens off screen to PNGs in `app/build/screens`, so a change of look can be seen without a display. */
class ScreenshotsTest {
    @AfterEach
    fun restoreTheme() {
        Tokens.dark = true
    }

    @Test
    fun `renders the factory screen in both themes to PNG files`() {
        val out = Path.of("build", "screens").also { Files.createDirectories(it) }
        listOf(true to "dark", false to "light").forEach { (dark, name) ->
            val file = out.resolve("factory-$name.png")
            render(1440, 900, file) { FactoryContent(dark) }
            assertThat(Files.size(file)).isGreaterThan(10_000L)
            val panel = out.resolve("panel-$name.png")
            render(1440, 900, panel) { FactoryContent(dark, openCard = true) }
            assertThat(Files.size(panel)).isGreaterThan(10_000L)
            val narrow = out.resolve("factory-$name-1000.png")
            render(1000, 700, narrow) { FactoryContent(dark) }
            assertThat(Files.size(narrow)).isGreaterThan(10_000L)
        }
    }

    @Composable
    private fun FactoryContent(dark: Boolean, openCard: Boolean = false) {
        Tokens.dark = dark
        Column(Modifier.fillMaxSize().background(Tokens.ground)) {
            AppBar(AppSection.BOARD, feedAvailable = true, onSelect = {}, onSettings = {}, onSearch = {}, theme = ThemeChoice.SYSTEM, onTheme = {})
            Box(Modifier.weight(1f)) {
                FactoryScreen(
                    view = ScreenFixtures.view(),
                    pulse = ScreenFixtures.pulse(),
                    connected = true,
                    openCardId = if (openCard) "7c113339-0000" else null,
                    panelWidth = 520.dp,
                    sidePanel = { width ->
                        if (openCard) {
                            CardSidePanel(
                                "7c113339-0000",
                                CardDetailState.Loaded(ScreenFixtures.detail()),
                                tab = PanelTab.SUMMARY,
                                width = width,
                                onTab = {},
                                onResize = {},
                                onClose = {},
                            )
                        }
                    },
                )
            }
        }
    }

    private fun render(width: Int, height: Int, file: Path, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f), content = content)
        try {
            scene.render(0L)
            val image = scene.render(16_000_000L)
            Files.write(file, requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed for $file" }.bytes)
        } finally {
            scene.close()
        }
    }
}
