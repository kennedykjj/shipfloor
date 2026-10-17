package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore

/**
 * Build and test of every layer the card touched, scope and spec gates, over the worktree after the sync with main.
 * Each result is recorded as a GATE_FINISHED event.
 */
class ProjectValidateGates(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val executor: GateExecutor,
    private val changedPaths: ChangedPaths,
    private val clock: Clock,
    private val runDir: Path,
    private val baseRevision: String = "origin/main",
) : ValidateGates {

    override fun evaluate(cardId: String, worktree: Path): ValidateGateResults {
        val changed = (changedPaths.between(worktree, baseRevision) + changedPaths.untracked(worktree)).distinct()
        val touched = project.layers.filter { layer -> changed.any { it.startsWith(layer.path) } }
        val dir = runDir.resolve(cardId)
        val layerResults = touched.flatMap { layer -> listOf("build", "test").map { task -> layerGate(cardId, worktree, dir, layer, task) } }
        return ValidateGateResults(layerResults.flatMap { it.results }) +
            scopeGate(cardId, changed, touched.map { it.name }.toSet()) +
            specGates(cardId, worktree, changed)
    }

    private fun layerGate(cardId: String, worktree: Path, dir: Path, layer: ProjectLayer, task: String): ValidateGateResults {
        val name = "$task-${layer.name}"
        val spec = GateSpec(name, listOf("./gradlew", ":${layer.name}:$task", "--console=plain"), worktree, TIMEOUT)
        return ValidateGateResults.fromExecutor(name, executor.execute(cardId, spec, dir))
    }

    private fun scopeGate(cardId: String, changed: List<String>, layers: Set<String>): ValidateGateResults =
        ValidateGateResults.fromScope(ScopeGate.check(project, changed, layers)).also { record(cardId, it) }

    private fun specGates(cardId: String, worktree: Path, changed: List<String>): ValidateGateResults {
        val specPath = changed.filter { it.startsWith(SPEC_DIR) && it.endsWith("-design.md") }
            .filter { Files.exists(worktree.resolve(it)) }
            .maxOrNull()
        val results = if (specPath == null) {
            ValidateGateResults(listOf(GateResult("test-plan", false, "no spec file under $SPEC_DIR")))
        } else {
            val layers = project.layers.map { SpecLayer(it.name, it.path, listOf("${it.path.trimEnd('/')}/**")) }
            ValidateGateResults.fromSpec(SpecGates.check(Files.readString(worktree.resolve(specPath)), layers))
        }
        return results.also { record(cardId, it) }
    }

    private fun record(cardId: String, results: ValidateGateResults) = results.results.forEach { record(cardId, it) }

    private fun record(cardId: String, result: GateResult) {
        val card = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val payload = buildMap {
            put("gate", result.name)
            put("verdict", if (result.passed) "PASS" else "FAIL")
            result.reason?.let { put("summary", it) }
        }
        val event = Event(EventId(UUID.randomUUID().toString()), clock.instant(), Actor.Factory("gate"), EventType.GATE_FINISHED, payload)
        recorder.record(cardId, card, event)
    }

    private companion object {
        const val SPEC_DIR = "docs/superpowers/specs/"
        val TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
