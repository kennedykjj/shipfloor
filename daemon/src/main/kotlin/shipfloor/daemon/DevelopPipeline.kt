package shipfloor.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import shipfloor.core.AcceptancePatterns
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.InvalidAcceptancePatternException
import shipfloor.core.InvalidGateInputException
import shipfloor.core.Milestone
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.store.CardEventStore

/** Judges the worktree after the sessions of a develop round; the verdict is the gates', never the agent's report. */
interface DevelopGates {
    /** Runs after the QA session: the acceptance tests QA wrote must fail for the right reason. */
    fun red(cardId: String, worktree: Path): List<GateResult>

    /** The project layers the card's spec plans tests for: the only ones that get an engineer session. */
    fun layers(worktree: Path): List<ProjectLayer>

    /** Runs after the engineer sessions: tests and build of [layer], acceptance-edit and scope. */
    fun green(cardId: String, layer: ProjectLayer, worktree: Path): List<GateResult>
}

/** The commit that is the worktree's HEAD, or null when it cannot be read. */
fun interface WorktreeHead {
    fun of(worktree: Path): String?

    /** The commit the daemon made for the QA tests (its message carries [QA_COMMIT_TRAILER]), or null. */
    fun qaCommit(worktree: Path): String? = null

    companion object {
        const val QA_COMMIT_TRAILER = "shipfloor-stage: qa"
    }
}

/** `git rev-parse HEAD` and `git log --grep` without a shell. */
class GitWorktreeHead(private val timeoutSeconds: Long = 10) : WorktreeHead {
    override fun of(worktree: Path): String? = git(worktree, "rev-parse", "HEAD")

    /** Only the card's own commits count: those on the first-parent chain that the base does not already have. */
    override fun qaCommit(worktree: Path): String? {
        val fork = BASES.firstNotNullOfOrNull { base -> git(worktree, "merge-base", "HEAD", base) } ?: return null
        return git(
            worktree, "log", "-n", "1", "--first-parent", "--format=%H", "--fixed-strings",
            "--grep=${WorktreeHead.QA_COMMIT_TRAILER}", "$fork..HEAD",
        )
    }

    private companion object {
        val BASES = listOf("origin/main", "main")
    }

    private fun git(worktree: Path, vararg args: String): String? {
        val output = Files.createTempFile("worktree-head", ".out")
        try {
            val process = try {
                ProcessBuilder(listOf("git", "-C", worktree.toString()) + args)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            } catch (e: IOException) {
                return null
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            return Files.readString(output).trim().takeIf { it.isNotEmpty() }
        } finally {
            Files.deleteIfExists(output)
        }
    }
}

/**
 * Real develop gates. Build and test commands come from the project's layers (read from `main`'s factory.yml
 * at registration, never from the worktree) and run without a shell. The `{selector}` marker of a test command
 * becomes the simple name of each test class the spec's Test plan lists for the layer.
 */
@Suppress("LongParameterList")
class RealDevelopGates(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val executor: GateExecutor,
    private val runner: GateRunner,
    private val changedPaths: ChangedPaths,
    private val runDir: Path,
    private val clock: Clock,
    private val head: WorktreeHead = GitWorktreeHead(),
    private val keepAssertions: Regex = DEFAULT_ASSERTIONS,
    private val baseRevision: String = "origin/main",
) : DevelopGates {

    private val qaCommits = ConcurrentHashMap<String, String>()

    override fun layers(worktree: Path): List<ProjectLayer> {
        val plan = testPlan(worktree)
        return project.layers.filter { plan.classesOf(it).isNotEmpty() }
    }

    override fun red(cardId: String, worktree: Path): List<GateResult> {
        head.of(worktree)?.let { qaCommits[cardId] = it }
        val plan = testPlan(worktree)
        val layers = project.layers.filter { plan.classesOf(it).isNotEmpty() }
        if (layers.isEmpty()) return listOf(GateResult("acceptance-red", false, "no_acceptance_tests"))
        return layers.map { layer ->
            runTests(cardId, layer, worktree, plan, "red") { GateCheck.Red(it) }
        }
    }

    override fun green(cardId: String, layer: ProjectLayer, worktree: Path): List<GateResult> {
        val plan = testPlan(worktree)
        val results = mutableListOf<GateResult>()
        if (plan.classesOf(layer).isNotEmpty()) {
            results += runTests(cardId, layer, worktree, plan, "tests") { GateCheck.Green(it) }
            results += acceptanceEdit(cardId, layer, worktree, plan)
        }
        layer.build?.let { results += runBuild(cardId, layer, it, worktree) }
        results += scope(cardId, worktree, plan)
        return results
    }

    private fun runTests(
        cardId: String,
        layer: ProjectLayer,
        worktree: Path,
        plan: TestPlan,
        suffix: String,
        check: (AcceptancePatterns) -> GateCheck,
    ): GateResult {
        val name = gateName(layer, suffix)
        val test = layer.test ?: return GateResult(name, false, "no_test_command")
        val classes = plan.classesOf(layer)
        if (classes.any { !SELECTOR.matches(it) }) return GateResult(name, false, "invalid_selector")
        val patterns = try {
            AcceptancePatterns.of(layer.ran ?: DEFAULT_RAN)
        } catch (e: InvalidAcceptancePatternException) {
            return GateResult(name, false, "invalid_ran_pattern")
        }
        val spec = GateSpec(name, substituteSelector(test, classes), worktree.resolve(layer.path), TEST_TIMEOUT, check(patterns))
        return execute(cardId, spec)
    }

    private fun runBuild(cardId: String, layer: ProjectLayer, command: List<String>, worktree: Path): GateResult =
        execute(cardId, GateSpec(gateName(layer, "build"), command, worktree.resolve(layer.path), BUILD_TIMEOUT))

    private fun acceptanceEdit(cardId: String, layer: ProjectLayer, worktree: Path, plan: TestPlan): GateResult {
        val qaCommit = head.qaCommit(worktree) ?: qaCommits[cardId] ?: return GateResult(EDIT_GATE, false, "no_qa_commit")
        val files = plan.filesOf(layer)
        val verdict = try {
            AcceptanceEditGate(executor, runner).check(cardId, worktree, qaCommit, files, keepAssertions, runDir.resolve(cardId))
        } catch (e: InvalidGateInputException) {
            return GateResult(EDIT_GATE, false, "invalid_input")
        }
        return result(EDIT_GATE, verdict)
    }

    private fun scope(cardId: String, worktree: Path, plan: TestPlan): GateResult {
        val changed = (changedPaths.between(worktree, baseRevision) + changedPaths.untracked(worktree)).distinct()
        val stageLayers = project.layers.filter { plan.classesOf(it).isNotEmpty() }.map { it.name }.toSet()
        val result = when (val verdict = ScopeGate.check(project, changed, stageLayers)) {
            ScopeVerdict.Pass -> GateResult("scope", true, null)
            is ScopeVerdict.Violations ->
                GateResult("scope", false, "forbidden=${verdict.forbidden.size} outside=${verdict.outsideLayers.size}")
        }
        store.card(cardId)?.let { recordScope(cardId, it, result) }
        return result
    }

    private fun recordScope(cardId: String, card: Card, result: GateResult) {
        val payload = buildMap {
            put("gate", result.name)
            put("verdict", if (result.passed) "PASS" else "FAIL")
            result.reason?.let {
                put("reason", it)
                put("summary", it)
            }
        }
        recorder.record(
            cardId,
            card,
            Event(EventId(UUID.randomUUID().toString()), clock.instant(), Actor.Factory("gate"), EventType.GATE_FINISHED, payload),
        )
    }

    private fun execute(cardId: String, spec: GateSpec): GateResult =
        result(spec.name, executor.execute(cardId, spec, runDir.resolve(cardId)))

    private fun result(name: String, verdict: GateVerdict): GateResult =
        when (verdict) {
            GateVerdict.Pass -> GateResult(name, true, null)
            is GateVerdict.Fail -> GateResult(name, false, verdict.reason)
        }

    private fun testPlan(worktree: Path): TestPlan {
        val specDir = worktree.resolve(SPEC_DIR)
        if (!Files.isDirectory(specDir)) return TestPlan(emptyList())
        val all = Files.list(specDir).use { entries ->
            entries.filter { it.fileName.toString().endsWith(".md") }.toList()
        }
        // The card's own spec, not whichever spec file is newest: a merge of the base brings the specs of other cards in.
        val own = try {
            (changedPaths.between(worktree, baseRevision) + changedPaths.untracked(worktree)).distinct()
                .filter { it.startsWith("$SPEC_DIR/") && it.endsWith(".md") }
                .map { worktree.resolve(it) }
                .filter { Files.isRegularFile(it) }
        } catch (e: ChangedPathsException) {
            emptyList()
        }
        val spec = own.ifEmpty { all }.maxByOrNull { Files.getLastModifiedTime(it).toMillis() } ?: return TestPlan(emptyList())
        return TestPlan.parse(Files.readString(spec))
    }

    private fun gateName(layer: ProjectLayer, suffix: String): String =
        "${layer.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "layer" }}-$suffix"

    /** Rows of the spec's Test plan table: the layer and the test file of each acceptance criterion. */
    class TestPlan(private val rows: List<Pair<String, String>>) {
        fun filesOf(layer: ProjectLayer): List<String> = rows.filter { it.first == layer.name }.map { it.second }.distinct()

        /** Simple class names: the file name without extension. */
        fun classesOf(layer: ProjectLayer): List<String> =
            filesOf(layer).map { it.substringAfterLast('/').substringBeforeLast('.') }.distinct()

        companion object {
            fun parse(specText: String): TestPlan {
                val lines = specText.lines()
                val start = lines.indexOfFirst { it.trim() == "## Test plan" }
                if (start < 0) return TestPlan(emptyList())
                val rows = lines.drop(start + 1)
                    .takeWhile { !it.trimStart().startsWith("## ") }
                    .filter { it.trim().startsWith("|") }
                    .map { line -> line.trim().trim('|').split('|').map { it.trim() } }
                    .filter { it.size >= 3 && it[0].startsWith("AC-") }
                    .map { it[1] to it[2].trim('`').trim() }
                    .filter { it.second.isNotEmpty() }
                return TestPlan(rows)
            }
        }
    }

    companion object {
        const val SELECTOR_MARKER = "{selector}"
        private const val SPEC_DIR = "docs/superpowers/specs"
        private const val EDIT_GATE = "acceptance-edit"
        private const val DEFAULT_RAN = "PASSED"
        private val SELECTOR = Regex("[A-Za-z0-9_]+")
        private val TEST_TIMEOUT: Duration = Duration.ofMinutes(20)
        private val BUILD_TIMEOUT: Duration = Duration.ofMinutes(20)
        val DEFAULT_ASSERTIONS = Regex("assert|assertThat|assertEquals|@Disabled|@Ignore|@Test\\(enabled")

        /**
         * Replaces the marker by each class name; the flag token before a bare marker (`--tests`) repeats with it.
         * No shell is involved: every element stays one argument.
         */
        fun substituteSelector(command: List<String>, classes: List<String>): List<String> {
            val result = mutableListOf<String>()
            var index = 0
            while (index < command.size) {
                val token = command[index]
                if (!token.contains(SELECTOR_MARKER)) {
                    result += token
                    index++
                    continue
                }
                val flag = result.lastOrNull()?.takeIf { token == SELECTOR_MARKER && it.startsWith("-") }
                if (flag != null) result.removeAt(result.lastIndex)
                classes.forEach { name ->
                    if (flag != null) result += flag
                    result += token.replace(SELECTOR_MARKER, name)
                }
                index++
            }
            return result
        }
    }
}

/** One pass of the develop stage: QA writes red tests, engineers implement per layer, gates move the card. */
@Suppress("LongParameterList")
private const val REPAIR_AFTER_GREEN_FAILURES = 2
private const val QA_REPAIR_ROLE = "QA (reparo dos testes): a implementação já existe e os gates verdes falharam mais de uma vez. " +
    "Se a causa é um defeito do próprio teste (preparação, fixture, diretório temporário repetido, id duplicado), " +
    "corrija só o teste, sem enfraquecer nem remover asserção; se a causa é a implementação, não mude nada"
internal const val MAX_FEEDBACK_GATES = 3
internal const val MAX_FEEDBACK_CHARS = 1500
private const val QA_ROLE = "QA (passo vermelho): escreva só os testes de aceitação de cada critério, sem código de produção"

class DevelopPipeline(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val worktrees: WorktreeManager,
    private val sessions: McpSessions,
    private val mcpPort: Int,
    private val runtime: Runtime,
    private val costRecorder: RunCostRecorder,
    private val gates: DevelopGates,
    private val modelStepUp: ModelStepUp,
    private val configDir: Path,
    private val clock: Clock,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val log: DaemonLog = StderrDaemonLog,
    private val runtimeName: String = "claude",
    private val milestones: ExternalMilestones? = null,
    private val commit: WorktreeCommit = WorktreeCommit.None,
) {
    init {
        require(maxRounds > 0) { "maxRounds must be positive, got $maxRounds" }
    }

    fun run(cardId: String) {
        val card = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        when (card.column) {
            Column.READY_FOR_DEV -> {
                move(cardId, Column.CODING)
                milestones?.reached(cardId, Milestone.STARTED)
            }
            Column.CODING -> Unit
            else -> {
                log.log("develop_skipped", mapOf("card" to cardId, "column" to card.column.name))
                return
            }
        }
        val worktree = worktrees.ensure(project, cardId, card.title)
        var failed: List<GateResult> = reworkReason(cardId)
        var redPassed = redProvenSinceSpec(cardId)
        var greenFailures = 0
        for (round in 1..maxRounds) {
            val outcome = playRound(cardId, worktree.path, redPassed, failed, qaRepair = greenFailures >= REPAIR_AFTER_GREEN_FAILURES)
            greenFailures = if (outcome.redPassed && outcome.failed.isNotEmpty()) greenFailures + 1 else 0
            redPassed = outcome.redPassed
            failed = outcome.failed
            log.log("develop_round", mapOf("card" to cardId, "round" to round.toString(), "failed" to failed.size.toString()))
            if (failed.isEmpty()) {
                move(cardId, Column.VALIDATION)
                return
            }
        }
        block(cardId, failed)
    }

    /** Why the validation sent the card back to Coding, when this run is the rework it asked for. */
    private fun reworkReason(cardId: String): List<GateResult> {
        val events = store.events(cardId)
        val lastValidation = events.indexOfLast { it.type == EventType.CARD_MOVED && it.payload["to"] == Column.VALIDATION.name }
        val back = events.withIndex().lastOrNull { (index, event) ->
            index > lastValidation && event.type == EventType.CARD_MOVED && event.payload["to"] == Column.CODING.name &&
                event.payload["from"] in setOf(Column.VALIDATION.name, Column.STAGED.name)
        }?.value ?: return emptyList()
        val reason = back.payload["reason"]?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(GateResult("validate", false, reason))
    }

    /**
     * True when every red gate of the card already passed since its spec was last produced: the QA tests were proven
     * red then and the implementation committed afterwards would make a new red gate impossible (a card sent back to
     * Coding after a blocked run, or after a restart, must not replay the QA).
     */
    private fun redProvenSinceSpec(cardId: String): Boolean {
        val events = store.events(cardId)
        val lastSpec = events.indexOfLast { it.type == EventType.CARD_MOVED && it.payload["to"] == Column.SPEC_REVIEW.name }
        val afterSpec = events.drop(lastSpec + 1)
        val reds = afterSpec.filter { it.type == EventType.GATE_FINISHED && it.payload["gate"]?.endsWith("-red") == true }
        if (reds.isEmpty()) return false
        return reds.groupBy { it.payload["gate"] }.values.all { runs -> runs.any { it.payload["verdict"] == "PASS" } }
    }

    /** Where a round ended: whether the tests were proven red, and the gates that failed (empty when all are green). */
    private data class Round(val redPassed: Boolean, val failed: List<GateResult>)

    /**
     * One round. Once the QA tests were proven red they stay committed, so a later round only reruns the engineers:
     * replaying the QA and the red gate over code that already implements the tests could never be red again.
     * The agents of the next round get the output of the gates that failed in the previous one.
     */
    private fun playRound(cardId: String, worktree: Path, redPassed: Boolean, previous: List<GateResult>, qaRepair: Boolean): Round {
        val model = modelStepUp.modelFor(cardId)
        val feedback = feedbackOf(previous)
        if (!redPassed) {
            runSession(cardId, worktree, model, QA_ROLE, feedback)
            commitSession(cardId, worktree, "test", "tests")
            val red = gates.red(cardId, worktree).filter { !it.passed }
            if (red.isNotEmpty()) return Round(false, red)
        }
        if (redPassed && qaRepair) {
            // Engineers cannot touch the QA tests: when the green gates failed twice, the test itself may be the defect.
            runSession(cardId, worktree, model, QA_REPAIR_ROLE, feedback)
            commitSession(cardId, worktree, "test", "tests")
        }
        val layers = gates.layers(worktree)
        layers.forEach { layer ->
            runSession(cardId, worktree, model, engineerRole(layer), feedback.takeIf { redPassed })
            commitSession(cardId, worktree, "feat", layer.name)
        }
        return Round(true, layers.flatMap { gates.green(cardId, it, worktree) }.filter { !it.passed })
    }

    private fun feedbackOf(failed: List<GateResult>): String? =
        failed.take(MAX_FEEDBACK_GATES).joinToString("\n") { "${it.name}: ${it.reason.orEmpty().take(MAX_FEEDBACK_CHARS)}" }
            .takeIf { it.isNotBlank() }

    /** The agents have no shell: what a session wrote becomes a commit of the card's branch here. */
    private fun commitSession(cardId: String, worktree: Path, type: String, scope: String) {
        if (type == "feat") RedStubFiles.removeEmptied(worktree)
        val card = store.card(cardId) ?: return
        val subject = ConventionText.commitSubject(
            project.policies.conventions.commitFormat, type, scope, card.title, cardId, ConventionText.ticketOf(card.references),
        )
        val message = if (type == "test") subject + "\n\n" + WorktreeHead.QA_COMMIT_TRAILER else subject
        if (!commit.commitAll(worktree, message)) log.log("develop_commit_failed", mapOf("card" to cardId, "scope" to scope))
    }

    private fun engineerRole(layer: ProjectLayer): String =
        "engenheiro da camada `${layer.name}` (caminho `${layer.path}`): implemente só dentro dela, até os testes de aceitação " +
            "passarem, sem alterar os testes da QA. Se a spec pede dependência nova ou outra versão de uma, o catálogo " +
            "`gradle/libs.versions.toml` (versão fixa) também é seu"

    private fun runSession(cardId: String, worktree: Path, model: String, role: String, feedback: String?) {
        val token = sessions.open(project.repo, "develop", cardId)
        val config = writeConfig(token)
        try {
            val result = runtime.launch(SessionRequest("develop", worktree, model, config, StagePrompt.of("develop", cardId, role, feedback)))
            recordCost(cardId, model, result)
        } finally {
            runCatching { Files.deleteIfExists(config) }
            sessions.close(token)
        }
    }

    private fun writeConfig(token: String): Path {
        Files.createDirectories(configDir)
        val file = configDir.resolve("mcp-${UUID.randomUUID()}.json")
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val json = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject("shipfloor") {
                    put("type", "http")
                    put("url", "http://127.0.0.1:$mcpPort/mcp")
                    putJsonObject("headers") { put("Authorization", "Bearer $token") }
                }
            }
        }
        Files.writeString(file, json.toString())
        return file
    }

    private fun recordCost(cardId: String, model: String, result: SessionResult) {
        costRecorder.record(cardId, UUID.randomUUID().toString(), runtimeName, "develop", model, result)
    }

    private fun move(cardId: String, to: Column) {
        val current = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("develop"),
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to current.column.name, "to" to to.name),
        )
        recorder.record(cardId, current.movedTo(to), event)
        log.log("develop_moved", mapOf("card" to cardId, "to" to to.name))
    }

    private fun block(cardId: String, failed: List<GateResult>) {
        val current = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val reason = "gates_failed: " + failed.map { it.name }.distinct().joinToString(",")
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("develop"),
            type = EventType.CARD_BLOCKED,
            payload = mapOf("reason" to reason),
        )
        recorder.record(cardId, current, event)
        log.log("develop_blocked", mapOf("card" to cardId, "reason" to reason))
    }
}

/** Starts the develop pipeline on its own thread; the card's pool lock is released whatever the outcome. */
class DevelopStageLauncher(
    private val pipelineFor: (String) -> DevelopPipeline,
    pool: RunPool,
    log: DaemonLog = StderrDaemonLog,
    onRunFinished: (String) -> Unit = {},
) : StageLauncher {

    private val launcher = PipelineStageLauncher(
        "develop", pool, log, onRunFinished,
    ) { cardId -> pipelineFor(cardId).run(cardId) }

    override fun launch(stage: DispatchStage, cardId: String): LaunchedRun {
        require(stage == DispatchStage.DEVELOP) { "DevelopStageLauncher cannot launch stage $stage (card $cardId)" }
        return launcher.start(cardId)
    }
}
