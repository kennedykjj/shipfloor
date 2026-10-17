package shipfloor.app

import java.io.IOException
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import shipfloor.core.Column

/**
 * Shape the implementation must follow (see the plan's "Contrato"): `OwnerMoves.kt` (`ownerTargets`, `needsNote`,
 * `MoveOutcome`, `NotSentCause`, `BoardEdits`, `OwnerMoveState`, `MoveNotice`, `OwnerMoveController`) and
 * `OwnerCommands.kt` (`OwnerCommands`, `ownerMoveRequest`, `SocketOwnerCommands`). No UI, no sleeps.
 *
 * Closure screen (#71), socket contract in `OwnerCommands.kt`: `closureDraftRequest(cardId)`,
 * `closureConfirmRequest(cardId, draftHash, proof)` and `SocketClosureCommands(socket, token)`. A confirm answered with
 * `ok:false` and an error starting with "draft changed" is `ConfirmOutcome.DraftChanged`; any other error is
 * `ConfirmOutcome.Failed(error)`. A draft error is `DraftOutcome.Refused(error)`.
 *
 * Card view actions (#67), also in `OwnerMoves.kt`: `SpecReviewController(commands: OwnerCommands, io: CoroutineDispatcher)`
 * with `state: StateFlow<SpecReviewState(notePending, sending, sent, notice)>`; `suspend approve(cardId)`;
 * `suspend requestChange(cardId, note: String?)` (a blank note opens the note field and sends nothing); `cancel()`;
 * `dismissNotice()`; `onDetail(detail: CardDetail)` clears `sent` once the card left `SPEC_REVIEW`. There is no
 * optimistic edit: the column only changes when the daemon event reaches the detail. The pure
 * `specReviewActionsVisible(state: CardDetailState)` is true only for a loaded card in `SPEC_REVIEW`.
 */
class OwnerMovesTest {
    @Test
    fun `AC-1 the owner moves are exactly the four owner-only ones`() {
        assertThat(ownerTargets(Column.INBOX)).containsExactly(Column.READY_FOR_REFINEMENT)
        assertThat(ownerTargets(Column.SPEC_REVIEW)).containsExactlyInAnyOrder(Column.READY_FOR_DEV, Column.READY_FOR_REFINEMENT)
        assertThat(ownerTargets(Column.STAGED)).containsExactly(Column.READY_FOR_PROD)
    }

    @Test
    fun `AC-1 a drop sends the move to the daemon and the card is in the new column right away`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.STAGED), BoardFixtures.aCard("b", Column.READY_FOR_PROD))
        var columnWhileInFlight: List<String> = emptyList()
        fx.commands.reply = {
            columnWhileInFlight = fx.edits.current.cardsIn(Column.READY_FOR_PROD).map { it.id }
            MoveOutcome.Accepted
        }

        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.READY_FOR_PROD)
        fx.controller.submit()

        assertThat(fx.commands.calls).containsExactly(SentMove("a", Column.STAGED, Column.READY_FOR_PROD, null))
        assertThat(columnWhileInFlight).containsExactly("b", "a")
        assertThat(fx.edits.current.cardsIn(Column.READY_FOR_PROD).map { it.id }).containsExactly("b", "a")
        assertThat(fx.edits.current.cardsIn(Column.STAGED)).isEmpty()
        assertThat(fx.controller.state.value.held).isNull()
        assertThat(fx.controller.state.value.notice).isNull()
    }

    @Test
    fun `AC-1 the request carries the owner actor and proof and picks the daemon command for the move`() {
        fun parse(from: Column, to: Column, note: String? = null): JsonObject =
            Json.parseToJsonElement(ownerMoveRequest("c1", from, to, note, "tok-123")).jsonObject

        val move = parse(Column.STAGED, Column.READY_FOR_PROD)
        assertThat(move["cmd"]?.jsonPrimitive?.content).isEqualTo("card.move")
        assertThat(move["to"]?.jsonPrimitive?.content).isEqualTo("READY_FOR_PROD")
        assertThat(parse(Column.INBOX, Column.READY_FOR_REFINEMENT)["cmd"]?.jsonPrimitive?.content).isEqualTo("card.move")

        val approve = parse(Column.SPEC_REVIEW, Column.READY_FOR_DEV)
        assertThat(approve["cmd"]?.jsonPrimitive?.content).isEqualTo("card.approve")

        val change = parse(Column.SPEC_REVIEW, Column.READY_FOR_REFINEMENT, "cover offline")
        assertThat(change["cmd"]?.jsonPrimitive?.content).isEqualTo("card.request-change")
        assertThat(change["note"]?.jsonPrimitive?.content).isEqualTo("cover offline")

        listOf(move, approve, change).forEach {
            assertThat(it["actor"]?.jsonPrimitive?.content).isEqualTo("owner")
            assertThat(it["proof"]?.jsonPrimitive?.content).isEqualTo("tok-123")
            assertThat(it["id"]?.jsonPrimitive?.content).isEqualTo("c1")
        }
    }

    @Test
    fun `AC-1 the socket commands send the request with the token read from disk and read the daemon answer`() {
        val dir = Files.createTempDirectory("shipfloorm")
        Files.writeString(dir.resolve("owner.token"), "secret-token\n")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { """{"ok":true,"result":{"id":"a","title":"t","column":"READY_FOR_PROD"}}""" }
        try {
            val commands = SocketOwnerCommands(dir.resolve("daemon.sock"), dir.resolve("owner.token"))

            val outcome = commands.move("a", Column.STAGED, Column.READY_FOR_PROD, null)

            assertThat(outcome).isEqualTo(MoveOutcome.Accepted)
            val sent = Json.parseToJsonElement(daemon.requests.single()).jsonObject
            assertThat(sent["cmd"]?.jsonPrimitive?.content).isEqualTo("card.move")
            assertThat(sent["proof"]?.jsonPrimitive?.content).isEqualTo("secret-token")
            assertThat(sent["actor"]?.jsonPrimitive?.contentOrNull).isEqualTo("owner")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-4 a daemon error answer is a refusal carrying the daemon reason`() {
        val dir = Files.createTempDirectory("shipfloorm")
        Files.writeString(dir.resolve("owner.token"), "secret-token\n")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { """{"ok":false,"error":"move not allowed"}""" }
        try {
            val outcome = SocketOwnerCommands(dir.resolve("daemon.sock"), dir.resolve("owner.token"))
                .move("a", Column.STAGED, Column.READY_FOR_PROD, null)

            assertThat(outcome).isEqualTo(MoveOutcome.Refused("move not allowed"))
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-2 M offers only the allowed destinations, a choice is sent on confirm and Esc sends nothing`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.SPEC_REVIEW))

        fx.controller.hold("a")
        val held = fx.controller.state.value
        assertThat(held.held?.id).isEqualTo("a")
        assertThat(held.targets).containsExactlyInAnyOrder(Column.READY_FOR_DEV, Column.READY_FOR_REFINEMENT)

        fx.controller.chooseTarget(Column.CODING)
        assertThat(fx.controller.state.value.selected).isNotEqualTo(Column.CODING)

        fx.controller.chooseTarget(Column.READY_FOR_DEV)
        assertThat(fx.controller.state.value.selected).isEqualTo(Column.READY_FOR_DEV)
        fx.controller.submit()
        assertThat(fx.commands.calls).containsExactly(SentMove("a", Column.SPEC_REVIEW, Column.READY_FOR_DEV, null))
        assertThat(fx.edits.current.cardsIn(Column.READY_FOR_DEV).map { it.id }).containsExactly("a")
    }

    @Test
    fun `AC-2 cancelling drops the held card without sending or moving anything`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.SPEC_REVIEW))
        val before = fx.edits.current

        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.READY_FOR_DEV)
        fx.controller.cancel()
        fx.controller.submit()

        assertThat(fx.commands.calls).isEmpty()
        assertThat(fx.edits.current).isEqualTo(before)
        assertThat(fx.controller.state.value.held).isNull()
        assertThat(fx.controller.state.value.status).isEqualTo("Nada enviado.")
    }

    @Test
    fun `AC-3 only Spec Review to Ready for Refinement needs a note`() {
        assertThat(needsNote(Column.SPEC_REVIEW, Column.READY_FOR_REFINEMENT)).isTrue()
        assertThat(needsNote(Column.SPEC_REVIEW, Column.READY_FOR_DEV)).isFalse()
        assertThat(needsNote(Column.INBOX, Column.READY_FOR_REFINEMENT)).isFalse()
        assertThat(needsNote(Column.STAGED, Column.READY_FOR_PROD)).isFalse()
    }

    @Test
    fun `AC-3 sending back without a note does not send, and with a note the card returns carrying it`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.SPEC_REVIEW))
        val before = fx.edits.current
        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.READY_FOR_REFINEMENT)

        fx.controller.submit(null)
        fx.controller.submit("   ")

        assertThat(fx.commands.calls).isEmpty()
        assertThat(fx.edits.current).isEqualTo(before)
        assertThat(fx.controller.state.value.notePending).isTrue()

        fx.controller.submit("  cover offline  ")

        assertThat(fx.commands.calls)
            .containsExactly(SentMove("a", Column.SPEC_REVIEW, Column.READY_FOR_REFINEMENT, "cover offline"))
        assertThat(fx.edits.current.cardsIn(Column.READY_FOR_REFINEMENT).map { it.id }).containsExactly("a")
    }

    @Test
    fun `AC-4 a refused move puts the card back and shows the daemon reason`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.STAGED))
        fx.commands.reply = { MoveOutcome.Refused("card is not in Staged") }

        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.READY_FOR_PROD)
        fx.controller.submit()

        assertThat(fx.edits.current.cardsIn(Column.STAGED).map { it.id }).containsExactly("a")
        assertThat(fx.edits.current.cardsIn(Column.READY_FOR_PROD)).isEmpty()
        assertThat(fx.controller.state.value.notice)
            .isEqualTo(MoveNotice(NoticeKind.REFUSED, "Não movido: card is not in Staged"))

        fx.controller.dismissNotice()
        assertThat(fx.controller.state.value.notice).isNull()
    }

    @Test
    fun `AC-4 a refusal does not overwrite the column a daemon event already took the card to`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.STAGED))
        fx.commands.reply = {
            fx.edits.edit { it.copy(cards = it.cards.map { c -> c.copy(column = Column.DONE) }) }
            MoveOutcome.Refused("too late")
        }

        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.READY_FOR_PROD)
        fx.controller.submit()

        assertThat(fx.edits.current.cardsIn(Column.DONE).map { it.id }).containsExactly("a")
        assertThat(fx.controller.state.value.notice?.text).isEqualTo("Não movido: too late")
    }

    @Test
    fun `AC-5 a card without an owner move is not held, offers no destination and sends nothing`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.CODING))
        val before = fx.edits.current

        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.DONE)
        fx.controller.submit()

        val state = fx.controller.state.value
        assertThat(state.held).isNull()
        assertThat(state.targets).isEmpty()
        assertThat(state.status).isEqualTo("Este card não tem movimento seu.")
        assertThat(ownerTargets(Column.CODING)).isEmpty()
        assertThat(fx.commands.calls).isEmpty()
        assertThat(fx.edits.current).isEqualTo(before)
    }

    @Test
    fun `AC-6 an unavailable daemon or a missing proof puts the card back and says it was not sent`() = runBlocking {
        val cases = mapOf(
            NotSentCause.DAEMON_UNAVAILABLE to "Movimento não enviado: o daemon está indisponível.",
            NotSentCause.NO_OWNER_PROOF to "Movimento não enviado: prova do dono não encontrada.",
        )
        cases.forEach { (cause, text) ->
            val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.INBOX))
            fx.commands.reply = { MoveOutcome.NotSent(cause) }

            fx.controller.hold("a")
            fx.controller.chooseTarget(Column.READY_FOR_REFINEMENT)
            fx.controller.submit()

            assertThat(fx.edits.current.cardsIn(Column.INBOX).map { it.id }).containsExactly("a")
            assertThat(fx.controller.state.value.notice).isEqualTo(MoveNotice(NoticeKind.NOT_SENT, text))
        }
    }

    @Test
    fun `AC-6 the socket commands report no daemon, and no token without touching the daemon`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val socket = dir.resolve("daemon.sock")
        val token = dir.resolve("owner.token")

        // Token present, nothing listening.
        Files.writeString(token, "tok\n")
        assertThat(SocketOwnerCommands(socket, token).move("a", Column.STAGED, Column.READY_FOR_PROD, null))
            .isEqualTo(MoveOutcome.NotSent(NotSentCause.DAEMON_UNAVAILABLE))

        // Token missing or empty, daemon up: nothing is sent.
        val daemon = FakeDaemon(socket) { """{"ok":true,"result":{}}""" }
        try {
            Files.delete(token)
            assertThat(SocketOwnerCommands(socket, token).move("a", Column.STAGED, Column.READY_FOR_PROD, null))
                .isEqualTo(MoveOutcome.NotSent(NotSentCause.NO_OWNER_PROOF))
            Files.writeString(token, "  \n")
            assertThat(SocketOwnerCommands(socket, token).move("a", Column.STAGED, Column.READY_FOR_PROD, null))
                .isEqualTo(MoveOutcome.NotSent(NotSentCause.NO_OWNER_PROOF))
            assertThat(daemon.requests).isEmpty()
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-1 the draft request names the card and the socket commands read the draft from the daemon answer`() {
        val request = Json.parseToJsonElement(closureDraftRequest("c1")).jsonObject
        assertThat(request["cmd"]?.jsonPrimitive?.content).isEqualTo("card.close.draft")
        assertThat(request["id"]?.jsonPrimitive?.content).isEqualTo("c1")

        val dir = Files.createTempDirectory("shipfloorm")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) {
            """{"ok":true,"result":{"status":"Entregue","summary":"Resumo","prLink":"https://github.com/o/r/pull/7","draftHash":"h1"}}"""
        }
        try {
            val outcome = SocketClosureCommands(dir.resolve("daemon.sock"), dir.resolve("owner.token")).draft("c1")

            assertThat(outcome).isEqualTo(DraftOutcome.Drafted("Entregue", "Resumo", "https://github.com/o/r/pull/7", "h1"))
            assertThat(Json.parseToJsonElement(daemon.requests.single()).jsonObject["cmd"]?.jsonPrimitive?.content)
                .isEqualTo("card.close.draft")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-2 a draft the daemon refuses carries the daemon reason`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { """{"ok":false,"error":"no_pr_recorded"}""" }
        try {
            val outcome = SocketClosureCommands(dir.resolve("daemon.sock"), dir.resolve("owner.token")).draft("c1")

            assertThat(outcome).isEqualTo(DraftOutcome.Refused("no_pr_recorded"))
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-3 the confirm request carries the owner actor, the proof and the displayed draft hash`() {
        val request = Json.parseToJsonElement(closureConfirmRequest("c1", "h1", "tok-123")).jsonObject

        assertThat(request["cmd"]?.jsonPrimitive?.content).isEqualTo("card.close.confirm")
        assertThat(request["id"]?.jsonPrimitive?.content).isEqualTo("c1")
        assertThat(request["actor"]?.jsonPrimitive?.content).isEqualTo("owner")
        assertThat(request["draftHash"]?.jsonPrimitive?.content).isEqualTo("h1")
        assertThat(request["proof"]?.jsonPrimitive?.content).isEqualTo("tok-123")
    }

    @Test
    fun `AC-3 the socket confirm reads the token at every send and is closed when the daemon accepts`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val token = dir.resolve("owner.token")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { """{"ok":true,"result":{"status":"closed"}}""" }
        try {
            val commands = SocketClosureCommands(dir.resolve("daemon.sock"), token)

            Files.writeString(token, "first-token\n")
            val first = commands.confirm("c1", "h1")
            Files.writeString(token, "second-token\n")
            val second = commands.confirm("c1", "h1")

            assertThat(first).isEqualTo(ConfirmOutcome.Closed)
            assertThat(second).isEqualTo(ConfirmOutcome.Closed)
            val sent = daemon.requests.map { Json.parseToJsonElement(it).jsonObject }
            assertThat(sent.map { it["proof"]?.jsonPrimitive?.content }).containsExactly("first-token", "second-token")
            assertThat(sent.map { it["draftHash"]?.jsonPrimitive?.content }).containsExactly("h1", "h1")
            assertThat(sent.map { it["actor"]?.jsonPrimitive?.content }).containsOnly("owner")
            assertThat(sent.map { it["cmd"]?.jsonPrimitive?.content }).containsOnly("card.close.confirm")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-4 a tracker error is a failure with the daemon reason and a changed draft is told apart`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val token = dir.resolve("owner.token")
        Files.writeString(token, "secret-token\n")
        var answer = """{"ok":false,"error":"tracker unavailable"}"""
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { answer }
        try {
            val commands = SocketClosureCommands(dir.resolve("daemon.sock"), token)

            assertThat(commands.confirm("c1", "h1")).isEqualTo(ConfirmOutcome.Failed("tracker unavailable"))

            answer = """{"ok":false,"error":"draft changed for card c1: request a new draft"}"""
            assertThat(commands.confirm("c1", "h1")).isEqualTo(ConfirmOutcome.DraftChanged)
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-5 a missing or empty token sends nothing and an absent daemon is reported as unavailable`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val socket = dir.resolve("daemon.sock")
        val token = dir.resolve("owner.token")

        // Token present, nothing listening.
        Files.writeString(token, "tok\n")
        assertThat(SocketClosureCommands(socket, token).confirm("c1", "h1"))
            .isEqualTo(ConfirmOutcome.NotSent(NotSentCause.DAEMON_UNAVAILABLE))
        assertThat(SocketClosureCommands(socket, token).draft("c1"))
            .isEqualTo(DraftOutcome.NotSent(NotSentCause.DAEMON_UNAVAILABLE))

        // Token missing or empty, daemon up: nothing is sent.
        val daemon = FakeDaemon(socket) { """{"ok":true,"result":{}}""" }
        try {
            Files.delete(token)
            assertThat(SocketClosureCommands(socket, token).confirm("c1", "h1"))
                .isEqualTo(ConfirmOutcome.NotSent(NotSentCause.NO_OWNER_PROOF))
            Files.writeString(token, "  \n")
            assertThat(SocketClosureCommands(socket, token).confirm("c1", "h1"))
                .isEqualTo(ConfirmOutcome.NotSent(NotSentCause.NO_OWNER_PROOF))
            assertThat(daemon.requests).isEmpty()
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-1 a daemon that accepts the connection and never answers gives NO_ANSWER within the deadline`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val socket = dir.resolve("daemon.sock")
        val token = dir.resolve("owner.token")
        Files.writeString(token, "tok\n")
        val release = CountDownLatch(1)
        val reached = CountDownLatch(1)
        val daemon = FakeDaemon(socket) {
            reached.countDown()
            release.await()
            """{"ok":true,"result":{}}"""
        }
        try {
            val commands = SocketOwnerCommands(socket, token, timeoutMillis = 200)

            val outcome = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                ThrowingSupplier { commands.move("a", Column.STAGED, Column.READY_FOR_PROD, null) },
            )

            assertThat(outcome).isEqualTo(MoveOutcome.NotSent(NotSentCause.NO_ANSWER))
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(daemon.requests).hasSize(1)
        } finally {
            release.countDown()
            daemon.close()
        }
    }

    @Test
    fun `AC-1 a move with no answer closes the connection to the silent daemon`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val token = dir.resolve("owner.token")
        Files.writeString(token, "tok\n")
        val daemon = SilentDaemon(dir.resolve("daemon.sock"))
        try {
            val outcome = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                ThrowingSupplier {
                    SocketOwnerCommands(dir.resolve("daemon.sock"), token, timeoutMillis = 200)
                        .move("a", Column.STAGED, Column.READY_FOR_PROD, null)
                },
            )

            assertThat(outcome).isEqualTo(MoveOutcome.NotSent(NotSentCause.NO_ANSWER))
            assertThat(daemon.hungUp.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(daemon.requests).hasSize(1)
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-2 a daemon that answers within the deadline still gives Accepted`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val token = dir.resolve("owner.token")
        Files.writeString(token, "tok\n")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { """{"ok":true,"result":{}}""" }
        try {
            val outcome = SocketOwnerCommands(dir.resolve("daemon.sock"), token, timeoutMillis = 2_000)
                .move("a", Column.STAGED, Column.READY_FOR_PROD, null)

            assertThat(outcome).isEqualTo(MoveOutcome.Accepted)
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-2 a daemon refusal within the deadline still carries the daemon reason`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val token = dir.resolve("owner.token")
        Files.writeString(token, "tok\n")
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) { """{"ok":false,"error":"too late"}""" }
        try {
            val outcome = SocketOwnerCommands(dir.resolve("daemon.sock"), token, timeoutMillis = 2_000)
                .move("a", Column.STAGED, Column.READY_FOR_PROD, null)

            assertThat(outcome).isEqualTo(MoveOutcome.Refused("too late"))
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `AC-3 a move with no answer puts the card back and tells the owner to check the board`() = runBlocking {
        val fx = ControllerFixtures(BoardFixtures.aCard("a", Column.STAGED))
        fx.commands.reply = { MoveOutcome.NotSent(NotSentCause.NO_ANSWER) }

        fx.controller.hold("a")
        fx.controller.chooseTarget(Column.READY_FOR_PROD)
        fx.controller.submit()

        assertThat(fx.edits.current.cardsIn(Column.STAGED).map { it.id }).containsExactly("a")
        assertThat(fx.edits.current.cardsIn(Column.READY_FOR_PROD)).isEmpty()
        assertThat(fx.controller.state.value.notice).isEqualTo(
            MoveNotice(NoticeKind.NOT_SENT, "Movimento sem resposta do daemon: confira o quadro antes de repetir."),
        )
    }

    @Test
    fun `AC-4 a refused connection is still DAEMON_UNAVAILABLE with a deadline set`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val token = dir.resolve("owner.token")
        Files.writeString(token, "tok\n")

        val outcome = SocketOwnerCommands(dir.resolve("daemon.sock"), token, timeoutMillis = 200)
            .move("a", Column.STAGED, Column.READY_FOR_PROD, null)

        assertThat(outcome).isEqualTo(MoveOutcome.NotSent(NotSentCause.DAEMON_UNAVAILABLE))
    }

    @Test
    fun `AC-4 a missing token is still NO_OWNER_PROOF and sends nothing to a daemon that does not answer`() {
        val dir = Files.createTempDirectory("shipfloorm")
        val release = CountDownLatch(1)
        val daemon = FakeDaemon(dir.resolve("daemon.sock")) {
            release.await()
            """{"ok":true,"result":{}}"""
        }
        try {
            val outcome = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                ThrowingSupplier {
                    SocketOwnerCommands(dir.resolve("daemon.sock"), dir.resolve("owner.token"), timeoutMillis = 200)
                        .move("a", Column.STAGED, Column.READY_FOR_PROD, null)
                },
            )

            assertThat(outcome).isEqualTo(MoveOutcome.NotSent(NotSentCause.NO_OWNER_PROOF))
            assertThat(daemon.requests).isEmpty()
        } finally {
            release.countDown()
            daemon.close()
        }
    }

    @Test
    fun `AC-1 the card view shows the actions only for a loaded card in Spec Review`() {
        assertThat(specReviewActionsVisible(CardDetailState.Loaded(SpecReviewFixtures.aDetail(Column.SPEC_REVIEW)))).isTrue()

        Column.entries.filter { it != Column.SPEC_REVIEW }.forEach {
            assertThat(specReviewActionsVisible(CardDetailState.Loaded(SpecReviewFixtures.aDetail(it)))).isFalse()
        }
        assertThat(specReviewActionsVisible(CardDetailState.Loading)).isFalse()
        assertThat(specReviewActionsVisible(CardDetailState.NotFound)).isFalse()
        assertThat(specReviewActionsVisible(CardDetailState.Disconnected)).isFalse()
    }

    @Test
    fun `AC-2 approving sends card approve as the owner and shows the approval as sent without moving the column`() = runBlocking {
        val fx = SpecReviewFixtures.controller()
        var stateWhileInFlight: SpecReviewState? = null
        fx.commands.reply = {
            stateWhileInFlight = fx.controller.state.value
            MoveOutcome.Accepted
        }

        fx.controller.approve("a")

        assertThat(fx.commands.calls).containsExactly(SentMove("a", Column.SPEC_REVIEW, Column.READY_FOR_DEV, null))
        assertThat(stateWhileInFlight?.sending).isTrue()
        val state = fx.controller.state.value
        assertThat(state.sent).isTrue()
        assertThat(state.sending).isFalse()
        assertThat(state.notice).isNull()
    }

    @Test
    fun `AC-3 asking for a change without a note sends nothing and keeps asking for the note`() = runBlocking {
        val fx = SpecReviewFixtures.controller()

        fx.controller.requestChange("a", null)
        assertThat(fx.controller.state.value.notePending).isTrue()
        fx.controller.requestChange("a", "   ")
        fx.controller.requestChange("a", "\n\t ")

        assertThat(fx.commands.calls).isEmpty()
        assertThat(fx.controller.state.value.notePending).isTrue()
        assertThat(fx.controller.state.value.sending).isFalse()
        assertThat(fx.controller.state.value.sent).isFalse()
    }

    @Test
    fun `AC-3 asking for a change with a note sends card request-change with the trimmed note`() = runBlocking {
        val fx = SpecReviewFixtures.controller()

        fx.controller.requestChange("a", "  cover offline  ")

        assertThat(fx.commands.calls)
            .containsExactly(SentMove("a", Column.SPEC_REVIEW, Column.READY_FOR_REFINEMENT, "cover offline"))
        assertThat(fx.controller.state.value.notePending).isFalse()
        assertThat(fx.controller.state.value.sent).isTrue()
    }

    @Test
    fun `AC-3 cancelling the note closes the field and sends nothing`() = runBlocking {
        val fx = SpecReviewFixtures.controller()
        fx.controller.requestChange("a", null)

        fx.controller.cancel()

        assertThat(fx.controller.state.value).isEqualTo(SpecReviewState())
        assertThat(fx.commands.calls).isEmpty()
    }

    @Test
    fun `AC-4 a refused approval shows the daemon reason, frees the actions and is dismissable`() = runBlocking {
        val fx = SpecReviewFixtures.controller()
        fx.commands.reply = { MoveOutcome.Refused("card is not in Spec Review") }

        fx.controller.approve("a")

        val state = fx.controller.state.value
        assertThat(state.notice).isEqualTo(MoveNotice(NoticeKind.REFUSED, "Não movido: card is not in Spec Review"))
        assertThat(state.sending).isFalse()
        assertThat(state.sent).isFalse()

        fx.controller.dismissNotice()
        assertThat(fx.controller.state.value.notice).isNull()
    }

    @Test
    fun `AC-4 an unavailable daemon or a missing proof says not sent with the cause and frees the actions`() = runBlocking {
        val cases = mapOf(
            NotSentCause.DAEMON_UNAVAILABLE to "Movimento não enviado: o daemon está indisponível.",
            NotSentCause.NO_OWNER_PROOF to "Movimento não enviado: prova do dono não encontrada.",
        )
        cases.forEach { (cause, text) ->
            val fx = SpecReviewFixtures.controller()
            fx.commands.reply = { MoveOutcome.NotSent(cause) }

            fx.controller.requestChange("a", "cover offline")

            val state = fx.controller.state.value
            assertThat(state.notice).isEqualTo(MoveNotice(NoticeKind.NOT_SENT, text))
            assertThat(state.sending).isFalse()
            assertThat(state.sent).isFalse()
        }
    }

    @Test
    fun `AC-4 after a refusal the owner can act again and the next accepted command clears the notice`() = runBlocking {
        val fx = SpecReviewFixtures.controller()
        fx.commands.reply = { MoveOutcome.Refused("busy") }
        fx.controller.approve("a")
        fx.commands.reply = { MoveOutcome.Accepted }

        fx.controller.approve("a")

        assertThat(fx.commands.calls).hasSize(2)
        assertThat(fx.controller.state.value.notice).isNull()
        assertThat(fx.controller.state.value.sent).isTrue()
    }

    @Test
    fun `AC-5 a second click while a command is in flight sends nothing new`() = runBlocking {
        val fx = SpecReviewFixtures.controller()
        fx.commands.reply = {
            runBlocking {
                fx.controller.approve("a")
                fx.controller.requestChange("a", "cover offline")
            }
            MoveOutcome.Accepted
        }

        fx.controller.approve("a")

        assertThat(fx.commands.calls).containsExactly(SentMove("a", Column.SPEC_REVIEW, Column.READY_FOR_DEV, null))
    }
}

object SpecReviewFixtures {
    class Controller(val commands: FakeOwnerCommands, val controller: SpecReviewController)

    fun controller(): Controller {
        val commands = FakeOwnerCommands()
        return Controller(commands, SpecReviewController(commands, Dispatchers.Unconfined))
    }

    fun aDetail(column: Column, events: List<TimelineEvent> = emptyList()) = CardDetail(
        id = "a",
        title = "A card",
        column = column.name,
        spec = SpecView("valid", "Do the thing"),
        events = events,
        eventsTruncated = false,
        runs = emptyList(),
        total = CostTotal(CostStatus.KNOWN, BigDecimal.ZERO),
    )
}

data class SentMove(val cardId: String, val from: Column, val to: Column, val note: String?)

class FakeOwnerCommands : OwnerCommands {
    val calls = mutableListOf<SentMove>()
    var reply: () -> MoveOutcome = { MoveOutcome.Accepted }

    override fun move(cardId: String, from: Column, to: Column, note: String?): MoveOutcome {
        calls += SentMove(cardId, from, to, note)
        return reply()
    }
}

class FakeBoardEdits(var current: Board) : BoardEdits {
    override fun board(): Board? = current

    override fun edit(transform: (Board) -> Board) {
        current = transform(current)
    }
}

class ControllerFixtures(vararg cards: BoardCard) {
    val commands = FakeOwnerCommands()
    val edits = FakeBoardEdits(BoardFixtures.empty().applySnapshot(cards.toList()))
    val controller = OwnerMoveController(commands, edits, Dispatchers.Unconfined)
}

/** One request per connection, like the real daemon; [reply] gives the one answer line. */
class FakeDaemon(socket: Path, private val reply: (String) -> String) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(socket)) }

    init {
        Thread.ofPlatform().daemon().start {
            try {
                while (true) {
                    val client = server.accept()
                    val line = Channels.newInputStream(client).bufferedReader().readLine()
                    if (line != null) {
                        requests.add(line)
                        Channels.newOutputStream(client).apply {
                            write((reply(line) + "\n").toByteArray())
                            flush()
                        }
                    }
                    client.close()
                }
            } catch (e: IOException) {
                // closed by the test
            }
        }
    }

    fun close() = server.close()
}

/** Accepts one connection, reads the request and never answers; [hungUp] opens when the client closes its side. */
class SilentDaemon(socket: Path) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val hungUp = CountDownLatch(1)
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(socket)) }

    init {
        Thread.ofPlatform().daemon().start {
            try {
                server.accept().use { client ->
                    val reader = Channels.newInputStream(client).bufferedReader()
                    reader.readLine()?.let { requests.add(it) }
                    if (reader.read() == -1) hungUp.countDown()
                }
            } catch (e: IOException) {
                // a reset by the client is also a hang-up
                hungUp.countDown()
            }
        }
    }

    fun close() = server.close()
}
