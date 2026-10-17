package shipfloor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.util.prefs.Preferences
import shipfloor.core.NotificationType
import shipfloor.core.Column as BoardColumn

/** What the owner chose for the theme: follow the system, or force one. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** Keeps the owner's theme choice between runs, in the user's preferences; a failure leaves the system theme. */
object ThemePreference {
    private const val KEY = "theme"
    private val node get() = Preferences.userRoot().node("shipfloor/app")

    fun load(): ThemeChoice =
        runCatching { ThemeChoice.valueOf(node.get(KEY, ThemeChoice.SYSTEM.name)) }.getOrDefault(ThemeChoice.SYSTEM)

    fun save(choice: ThemeChoice) {
        runCatching { node.put(KEY, choice.name) }
    }
}

/** One theme of the app: the neutral scale, the single accent (what waits for the owner) and the outcome colors. */
class Palette(
    val ground: Color,
    val panel: Color,
    val raise: Color,
    val edge: Color,
    val edgeHi: Color,
    val text: Color,
    val soft: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentInk: Color,
    val sand: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
)

private val DARK_PALETTE = Palette(
    ground = Color(0xFF131417),
    panel = Color(0xFF1B1D21),
    raise = Color(0xFF24272C),
    edge = Color(0xFF2E3238),
    edgeHi = Color(0xFF434850),
    text = Color(0xFFECECEF),
    soft = Color(0xFFD0D2D6),
    muted = Color(0xFFA3A7AE),
    faint = Color(0xFF8C9098),
    accent = Color(0xFFF5A623),
    accentInk = Color(0xFF1A1100),
    sand = Color(0xFFFFB84D),
    ok = Color(0xFF3ECF8E),
    warn = Color(0xFFFFB84D),
    bad = Color(0xFFFF6B6B),
)

private val LIGHT_PALETTE = Palette(
    ground = Color(0xFFFAFAFA),
    panel = Color(0xFFFFFFFF),
    raise = Color(0xFFF4F4F4),
    edge = Color(0xFFE6E6E6),
    edgeHi = Color(0xFFCFCFCF),
    text = Color(0xFF171717),
    soft = Color(0xFF3F3F3F),
    muted = Color(0xFF5C5C5C),
    faint = Color(0xFF6B6B6B),
    accent = Color(0xFFF5A623),
    accentInk = Color(0xFF1A1100),
    sand = Color(0xFF9A5B00),
    ok = Color(0xFF14804A),
    warn = Color(0xFF9A5B00),
    bad = Color(0xFFCF222E),
)

/**
 * Color tokens of DESIGN.md ("The Board"): a neutral scale and one accent. [sand] is the accent as text (it stays
 * readable on both grounds); [accent] is the accent as a fill. Reads [dark], so a change of theme recomposes.
 */
object Tokens {
    var dark: Boolean by mutableStateOf(true)

    private val palette: Palette get() = if (dark) DARK_PALETTE else LIGHT_PALETTE

    val ground: Color get() = palette.ground
    val panel: Color get() = palette.panel
    val raise: Color get() = palette.raise
    val edge: Color get() = palette.edge
    val edgeHi: Color get() = palette.edgeHi
    val text: Color get() = palette.text
    val soft: Color get() = palette.soft
    val muted: Color get() = palette.muted
    val faint: Color get() = palette.faint
    val accent: Color get() = palette.accent
    val accentInk: Color get() = palette.accentInk
    val sand: Color get() = palette.sand
    val ok: Color get() = palette.ok
    val warn: Color get() = palette.warn
    val bad: Color get() = palette.bad

    // Stages are told apart by shape and word, not by hue: they share the neutral text colors.
    val refine: Color get() = palette.muted
    val develop: Color get() = palette.soft
    val validate: Color get() = palette.muted
}

private fun geist(file: String, weight: FontWeight) = Font("fonts/$file", weight)

val sans = FontFamily(
    geist("Geist-Regular.ttf", FontWeight.Normal),
    geist("Geist-Medium.ttf", FontWeight.Medium),
    geist("Geist-SemiBold.ttf", FontWeight.SemiBold),
    geist("Geist-SemiBold.ttf", FontWeight.Bold),
)
val mono = FontFamily(
    geist("GeistMono-Regular.ttf", FontWeight.Normal),
    geist("GeistMono-Medium.ttf", FontWeight.Medium),
    geist("GeistMono-Medium.ttf", FontWeight.SemiBold),
)

/** Every user-facing text of the app, in pt-BR. */
object Texts {
    const val TITLE = "Shipfloor"
    const val CONNECTING = "Conectando ao daemon…"
    const val CONNECTED = "Conectado ao daemon"
    const val DISCONNECTED = "Desconectado do daemon"

    const val BOARD = "Esteira"
    const val NAV_FACTORY = "Fábrica"
    const val LANE_REFINE = "Refinar"
    const val LANE_REFINE_SUB = "do pedido à spec aprovada"
    const val LANE_DELIVER = "Desenvolver e entregar"
    const val LANE_DELIVER_SUB = "da implementação ao Done"
    const val LANE_HAND_OFF = "segue na raia de baixo"
    const val DROP_HERE = "soltar aqui"
    const val SHOW_FEWER = "Mostrar menos"
    const val RELEASE = "Liberar"

    fun showOlder(count: Int): String = "Ver os $count mais antigos"
    const val NAV_SETTINGS = "Configurações"
    const val SEARCH_HINT = "Buscar card ou comando"
    const val SEARCH_SHORTCUT = "Ctrl K"
    const val THEME_SYSTEM = "Tema do sistema"
    const val THEME_LIGHT = "Tema claro"
    const val THEME_DARK = "Tema escuro"

    fun waitingForYou(count: Int): String = if (count == 1) "1 espera a sua decisão" else "$count esperam a sua decisão"
    const val COLUMN_MAP = "Mapa das colunas"
    const val WAITS_FOR_YOU = "Aguarda você"
    const val MEDIAN = "mediana"
    const val MEAN_WAIT = "espera média"
    const val FLOW_TITLE = "Onde o tempo vai"
    const val FLOW_PHRASE = "mediana por etapa hoje · "
    const val AGO = "há"
    const val BLOCKED = "bloqueado"
    const val COST_UNKNOWN = "custo desconhecido"
    const val EMPTY_COLUMN = "Vazia"
    const val EMPTY_BOARD = "Nenhum card na fábrica ainda."
    const val LOADING_BOARD = "Carregando a esteira…"
    const val STALE_BOARD = "Desconectado do daemon. Mostrando o último estado conhecido; pode estar desatualizado."

    fun goToColumn(column: BoardColumn, count: Int): String = "Ir para ${column.displayName}, ${cardCount(count)}"

    const val TAB_SUMMARY = "Resumo"
    const val TAB_SPEC = "Spec"
    const val TAB_EVENTS = "Eventos"
    const val TAB_RUNS = "Runs"
    const val CLOSE_PANEL = "Fechar painel"
    const val DECREASE = "Diminuir"
    const val INCREASE = "Aumentar"
    const val PREVIOUS_DAY_SHORT = "Dia anterior"
    const val RESIZE_PANEL = "Redimensionar painel"
    const val PANEL_DISCONNECTED = "Desconectado do daemon. O quadro ao lado segue utilizável; o painel volta quando o daemon voltar."
    const val PANEL_NOT_FOUND_EXIT = "O daemon não conhece este card. Feche o painel com Esc ou com o botão Fechar."
    const val FOOTER_DECISION = "Decisão"
    const val FOOTER_STAGED_HINT = "Mova o cartão no quadro (tecla M ou arraste) para decidir."

    fun panelTitle(id: String): String = "Card #$id"

    fun approveTo(column: BoardColumn): String = "Aprovar leva a ${column.displayName}"

    fun openCardHint(): String = "aberto no painel"

    const val SPEC = "Spec"
    const val TIMELINE = "Linha do tempo"
    const val RUNS = "Runs"
    const val TOTAL = "Total"
    const val SIGNED_BY_OWNER = "assinada pelo dono"
    const val LOADING_CARD = "Lendo o card…"
    const val BACK = "Voltar"
    const val NO_SPEC = "Este card ainda não tem spec."
    const val NO_EVENTS = "Nenhum evento registrado."
    const val NO_RUNS = "Nenhum run ainda."
    const val EVENTS_TRUNCATED = "Mostrando só os 500 eventos mais recentes."
    const val COST_UNKNOWN_REASON = "falta preço de um modelo"

    const val GATES = "Gates"
    const val NO_GATES = "Nenhum gate rodou ainda"
    const val CRITERIA = "Critérios"
    const val NO_DURATION = "—"
    const val GATE_OK = "ok"
    const val GATE_BAD = "bad"
    const val GATE_NOT_RUN = "não rodou"
    const val GATE_MANUAL = "manual"
    const val BLOCK_NO_REASON = "motivo não informado"
    const val PR_OPEN = "aberto, aguardando decisão"
    const val PR_MERGED = "mergeado"
    const val PARTIAL_COST = "parcial"

    fun pullRequest(number: Int): String = "PR #$number"

    const val FEED_TITLE = "Feed do dia"
    const val TODAY = "Hoje"
    const val PREVIOUS_DAY = "Dia anterior"
    const val NEXT_DAY = "Próximo dia"
    const val CURRENT_DAY = "Este é o dia atual"
    const val RECONNECTING = "Reconectando…"

    const val COST_TITLE = "Custo do dia"
    const val COST_BY_CARD = "Por card"
    const val COST_BY_STAGE = "Por estágio"
    const val COST_BY_DAY = "Por dia"
    const val COST_TOTAL = "Total do dia"
    const val ESTIMATE_TAG = "estimativa"
    const val ESTIMATE_SUFFIX = "est."
    const val PARTIAL = "parcial"
    const val UNKNOWN = "desconhecido"
    const val LIMIT_REACHED = "limite atingido"
    const val LIMIT_EXCEEDED = "limite ultrapassado"
    const val LIMIT_OF = "de"
    const val LOADING = "Lendo o feed…"
    const val EMPTY_DAY = "Nenhum evento neste dia."
    const val EMPTY_STAGE = "Nenhum evento neste estágio."
    const val ALL_STAGES = "Todos"
    const val ACTOR_OWNER = "dono"
    const val ACTOR_AGENT = "agente"
    const val ACTOR_FACTORY = "fábrica"
    const val ACTOR_ADAPTER = "adaptador"
    const val FEED_ERROR = "Não consegui ler o feed. Tentando de novo."
    const val PARTIAL_EXPLANATION = "Sem preço ou transcrição completa; o valor real pode ser maior."
    const val EARLIER_HISTORY_UNAVAILABLE = "Histórico anterior indisponível"
    const val FAILED = "falhou"

    const val NO_OWNER_MOVE = "Este card não tem movimento seu."
    const val NOTHING_SENT = "Nada enviado."
    const val NOTE_LABEL = "Nota para a refinagem"
    const val SEND_BACK = "Devolver"
    const val CANCEL = "Cancelar"
    const val DISMISS = "Entendi"

    const val SPEC_DECISION = "Decisão sobre a spec"
    const val APPROVE = "Aprovar"
    const val REQUEST_CHANGE = "Pedir mudança"
    const val SEND_NOTE = "Enviar"
    const val SENDING = "Enviando…"
    const val APPROVAL_SENT = "Aprovação enviada"
    const val CHANGE_SENT = "Pedido de mudança enviado"
    const val DISMISS_NOTICE = "Dispensar"
    const val NOTICE_REFUSED = "Recusado"
    const val NOTICE_NOT_SENT = "Não enviado"
    const val NOTE_REQUIRED = "A nota é obrigatória para pedir mudança."

    const val CLOSURE_OPEN = "Fechamento no tracker"
    const val CLOSURE_TITLE = "Fechamento"
    const val CLOSURE_LOADING = "Pedindo o rascunho ao daemon…"
    const val CLOSURE_STATUS = "Status"
    const val CLOSURE_SUMMARY = "Resumo"
    const val CLOSURE_PR = "Pull request"
    const val CLOSURE_NOTICE = "Isto é o que será postado no tracker; o título do card não é enviado."
    const val CLOSURE_CONFIRM = "Confirmar fechamento"
    const val CLOSURE_CONFIRMING = "Confirmando…"
    const val CLOSURE_CLOSED = "Fechado no tracker"

    fun closureRefused(reason: String): String = "O daemon não montou o rascunho de fechamento: $reason"

    fun closureFailed(reason: String): String =
        "A escrita no tracker falhou e o card segue em Done. Tente de novo. Resposta do daemon: $reason"

    fun closureNotSent(cause: NotSentCause): String =
        when (cause) {
            NotSentCause.DAEMON_UNAVAILABLE -> "Nada foi enviado: o daemon está indisponível. Confira com shipfloor doctor e tente de novo."
            NotSentCause.NO_OWNER_PROOF -> "Nada foi enviado: o arquivo do token do dono não foi encontrado. Rode shipfloor service install e tente de novo."
            NotSentCause.NO_ANSWER -> "O daemon não respondeu a tempo: confira o card antes de tentar de novo."
        }

    const val OPEN_SETTINGS = "Configurações"
    const val RELOAD = "Recarregar"
    const val DENSITY_CARDS = "Cartões"
    const val DENSITY_CHIPS = "Fichas"
    const val RELOADING = "Recarregando…"
    const val RELOAD_FAILED = "Não foi possível ler o quadro do daemon. Confira se o daemon está ativo e tente de novo."
    const val SETTINGS_TITLE = "Configurações"
    const val SETTINGS_LOADING = "Lendo as configurações…"
    const val SETTINGS_SAVING = "Salvando…"
    const val SETTINGS_SAVED = "Salvo. Vale a partir do próximo ciclo."
    const val UNREACHABLE = "Não consegui falar com o daemon. Seu rascunho continua aqui."
    const val UNREACHABLE_LOAD = "Não consegui falar com o daemon."
    const val REFUSED = "O daemon recusou o salvamento. Nada foi alterado."
    const val RETRY = "Tentar de novo"
    const val DISCARD = "Descartar"
    const val SAVE = "Salvar"
    const val ON = "Ligado"
    const val OFF = "Desligado"

    const val GROUP_POOL = "Pool de sessões"
    const val GROUP_HOURS = "Horário de trabalho"
    const val GROUP_COST = "Limites de custo"
    const val GROUP_NOTIFICATIONS = "Notificações"
    const val POOL_LABEL = "Sessões simultâneas"
    const val POOL_DESCRIPTION = "Total de sessões que a fábrica roda ao mesmo tempo."
    const val HOURS_LABEL = "Restringir ao horário"
    const val HOURS_DESCRIPTION = "Fora da janela, a fábrica não despacha trabalho novo."
    const val HOURS_START = "Início"
    const val HOURS_END = "Fim"
    const val COST_CARD = "Limite por card"
    const val COST_DAY = "Limite por dia"
    const val COST_DESCRIPTION = "Vazio: sem limite"
    const val COST_PREFIX = "US$"
    const val COST_SUFFIX = "est."
    const val NOTIFICATIONS_NOTE = "Só guarda as preferências. As notificações ainda não são entregues."
    const val QUIET_LABEL = "Horário silencioso"
    const val QUIET_DESCRIPTION = "Sem avisos entre o início e o fim."
    const val BAD_TIME = "use HH:MM"
    const val BAD_AMOUNT = "use um número"

    val DAY_NAMES = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")

    private const val SHORT_ID = 8

    fun newEvents(count: Int): String = if (count == 1) "1 novo evento" else "$count novos eventos"

    fun holding(id: String): String = "Segurando #${id.take(SHORT_ID)}. Setas escolhem o destino, Enter confirma, Esc cancela."

    fun refused(reason: String): String = "Não movido: $reason"

    fun notSent(cause: NotSentCause): String =
        when (cause) {
            NotSentCause.DAEMON_UNAVAILABLE -> "Movimento não enviado: o daemon está indisponível."
            NotSentCause.NO_OWNER_PROOF -> "Movimento não enviado: prova do dono não encontrada."
            NotSentCause.NO_ANSWER -> "Movimento sem resposta do daemon: confira o quadro antes de repetir."
        }

    /** What the move means to the owner, for the destination chooser and the drop button. */
    fun moveMeaning(from: BoardColumn, to: BoardColumn): String =
        when {
            from == BoardColumn.SPEC_REVIEW && to == BoardColumn.READY_FOR_DEV -> "Aprovar a spec"
            to == BoardColumn.READY_FOR_REFINEMENT && from == BoardColumn.SPEC_REVIEW -> "Devolver à refinagem"
            to == BoardColumn.READY_FOR_PROD -> "Liberar a entrega"
            else -> "Enviar à refinagem"
        }

    fun flowWait(percent: Int): String = "$percent% é espera por você"

    /** The accessibility reading of the flow bar: every stage with its duration, then the wait phrase. */
    internal fun flowDescription(split: TimeSplit): String {
        val stages = split.segments.joinToString(", ") { "${it.column.displayName} ${ColumnTimeLabel.duration(it.seconds)}" }
        return "$FLOW_TITLE: $stages. $FLOW_PHRASE${flowWait(split.waitPercent)}"
    }

    fun cardNotFound(id: String): String = "Card #$id não encontrado."

    fun cardCount(count: Int): String = if (count == 1) "1 card" else "$count cards"

    fun notificationLabel(type: NotificationType): String =
        when (type) {
            NotificationType.DECISION_WAITING -> "Decisão espera por você"
            NotificationType.RUN_FAILED -> "Run falhou"
            NotificationType.FACTORY_STOPPED -> "Fábrica parou"
        }

    fun changes(count: Int): String = if (count == 1) "1 alteração não salva" else "$count alterações não salvas"

    fun fixFields(count: Int): String = if (count == 1) "Corrija 1 campo para salvar" else "Corrija $count campos para salvar"

    private fun fieldLabel(field: String): String =
        when {
            field == "poolSize" -> GROUP_POOL
            field.startsWith("workHours") -> GROUP_HOURS
            field == "cost.perCardUsd" -> COST_CARD
            field == "cost.perDayUsd" -> COST_DAY
            else -> field
        }

    /** The daemon's refusal of one settings field, in the owner's words. */
    fun issueReason(field: String, reason: String): String {
        val message = when {
            field == "poolSize" -> "use de 1 a 64."
            field == "workHours" -> "o fim deve ser depois do início."
            field == "workHours.days" -> "escolha ao menos um dia."
            field == "workHours.zone" -> "fuso desconhecido."
            field.startsWith("cost.") && reason.contains("required") -> "informe um limite."
            field.startsWith("cost.") -> "deve ser maior que zero."
            else -> reason
        }
        return "${fieldLabel(field)}: $message"
    }

    /** Why the daemon is unreachable, what to do, and the command that does it, when there is one. */
    data class Explanation(val reason: String, val step: String, val command: String? = null)

    fun explain(reason: DisconnectReason): Explanation =
        when (reason) {
            DisconnectReason.SERVICE_NOT_INSTALLED -> Explanation("O serviço shipfloor não está instalado.", "Instale com", "shipfloor service install")
            DisconnectReason.START_FAILED -> Explanation("O serviço shipfloor não iniciou.", "Veja o estado com", "shipfloor doctor")
            DisconnectReason.START_TIMED_OUT -> Explanation("O daemon não respondeu a tempo.", "Veja o estado com", "shipfloor doctor")
            DisconnectReason.NOT_RESPONDING -> Explanation("O daemon parou de responder.", "A janela volta sozinha quando ele voltar.")
            DisconnectReason.NO_RUNTIME_DIR ->
                Explanation("Não encontrei o diretório de runtime do usuário (XDG_RUNTIME_DIR).", "Veja o estado com", "shipfloor doctor")
        }
}
