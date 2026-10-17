package shipfloor.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import shipfloor.adapters.ClaudeRuntime
import shipfloor.adapters.RuntimeProvider
import shipfloor.core.AssistantStateRules
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.SpecStore

/**
 * The running parts of the refine stage: the dispatcher restricted to REFINE and the MCP endpoint its agents use.
 * [dispatcherAllowed] is false when the price table could not be loaded: the dispatcher must then stay stopped.
 */
class RefineRuntime private constructor(
    val dispatcher: Dispatcher,
    private val endpoint: McpEndpoint,
    val dispatcherAllowed: Boolean,
    private val stepUp: ModelStepUp,
    private val deliveryTrigger: AutonomousDeliveryTrigger,
    val milestones: ExternalMilestones,
    val costSummary: CostSummaryQuery,
    val pulse: FactoryPulse,
    val docsDraft: ProjectDocsDraft,
) : AutoCloseable {

    override fun close() {
        dispatcher.close()
        deliveryTrigger.close()
        stepUp.close()
        endpoint.close()
    }

    companion object {
        private const val POOL_CAPACITY = 2
        private const val MODEL = "claude-sonnet-5-5"
        private val MODEL_LADDER = listOf(MODEL, "claude-opus-5-5")
        private const val DELIVERY_MAX_POLLS = 40
        private const val PRICES_FILE = "prices.yml"
        private val TICK: Duration = Duration.ofSeconds(30)
        private val SESSION_TIMEOUT: Duration = Duration.ofMinutes(30)

        /** The saved pool size, read on every admission; without a settings file or when it is unreadable, the default. */
        private fun poolCapacity(settings: SettingsStore?, log: DaemonLog): Int {
            if (settings?.exists() != true) return POOL_CAPACITY
            return try {
                settings.load().poolSize
            } catch (e: SettingsFileException) {
                log.log("settings_unreadable", mapOf("field" to "poolSize"))
                POOL_CAPACITY
            }
        }

        private sealed interface Prices {
            data class Loaded(val table: PriceTable) : Prices
            data class Failed(val field: String) : Prices
        }

        /** Wires dispatcher, launcher, pipeline and MCP endpoint; the dispatcher is returned stopped. */
        @Suppress("LongParameterList", "LongMethod")
        fun start(
            dataDir: Path,
            store: CardEventStore,
            commands: CardCommands,
            registry: ProjectRegistry,
            files: ProjectFileSource,
            database: Database,
            clock: Clock,
            source: ProjectConfigSource,
            env: Map<String, String>,
            log: DaemonLog = StderrDaemonLog,
            settings: SettingsStore? = null,
        ): RefineRuntime {
            val provider = RuntimeProvider.fromEnv(env)
            val recorder = commands.recorder
            val worktrees = WorktreeManager(dataDir.resolve("worktrees"), SystemWorktreeGit())
            val sessions = McpSessions(clock)
            val specStore = SpecStore(database, dataDir.resolve("specs"))
            val docsScratch = Files.createDirectories(dataDir.resolve("docs-draft-scratch"))
            val endpoint = McpServerFactory(
                sessions, store, specStore, registry, files, recorder, worktrees, clock, docsScratchRoot = docsScratch,
            ).start()
            val runtime = ClaudeRuntime(transcriptDir = dataDir.resolve("transcripts"), timeout = SESSION_TIMEOUT, clock = clock, provider = provider)
            val prices = loadPrices(dataDir.resolve(PRICES_FILE))
            val priceTable = (prices as? Prices.Loaded)?.table ?: PriceTable.parse("models: {}")
            if (prices is Prices.Failed) log.log("dispatcher_not_started", mapOf("field" to prices.field))
            val costRecorder = RunCostRecorder(store, recorder, priceTable, clock)
            val pool = RunPool({ poolCapacity(settings, log) }, store, clock, SystemProcessProbe, recorder = recorder)
            val changedPaths = GitChangedPaths()
            val control = ConfiguredDispatchControl(registry, source, env, store, recorder, clock, log, settings)
            val stepUp = ModelStepUp(MODEL_LADDER, store)
            commands.events.addListener(stepUp::onEvent)
            val gateRunner = ProcessGateRunner(log)
            val milestones = ExternalMilestones(
                store = store,
                recorder = recorder,
                trackerOf = { cardId -> ProjectDelivery.trackerOf(projectOf(cardId, store, registry), dataDir.resolve("tracker")) },
                policiesOf = { cardId -> projectOf(cardId, store, registry).policies },
                projectOf = { cardId -> projectOf(cardId, store, registry) },
                prLinkOf = { project, pr -> ProjectDelivery.pullRequestUrl(project, pr) },
                clock = clock,
                log = log,
                ownerProof = commands.ownerProof,
            )
            val refineLauncher = RefineStageLauncher(
                pipelineFor = { cardId ->
                    val project = projectOf(cardId, store, registry)
                    RefinePipeline(
                        project = project,
                        store = store,
                        recorder = recorder,
                        worktrees = worktrees,
                        sessions = sessions,
                        mcpPort = endpoint.port,
                        runtime = runtime,
                        costRecorder = costRecorder,
                        gates = SpecScopeRefineGates(project, store, recorder, changedPaths, clock, specs = specStore),
                        commit = SystemWorktreeCommit(),
                        configDir = dataDir.resolve("mcp"),
                        clock = clock,
                        model = MODEL,
                    )
                },
                pool = pool,
                log = log,
                onRunFinished = control::onRunFinished,
            )
            val developLauncher = DevelopStageLauncher(
                pipelineFor = { cardId ->
                    val project = projectOf(cardId, store, registry)
                    val gates = RealDevelopGates(
                        project = project,
                        store = store,
                        recorder = recorder,
                        executor = GateExecutor(store, recorder, clock, gateRunner, log),
                        runner = gateRunner,
                        changedPaths = changedPaths,
                        runDir = dataDir.resolve("gates"),
                        clock = clock,
                    )
                    DevelopPipeline(
                        project = project,
                        store = store,
                        recorder = recorder,
                        worktrees = worktrees,
                        sessions = sessions,
                        mcpPort = endpoint.port,
                        runtime = runtime,
                        costRecorder = costRecorder,
                        gates = gates,
                        modelStepUp = stepUp,
                        configDir = dataDir.resolve("mcp"),
                        clock = clock,
                        log = log,
                        milestones = milestones,
                        commit = SystemWorktreeCommit(),
                    )
                },
                pool = pool,
                log = log,
                onRunFinished = control::onRunFinished,
            )
            val branchSync = SystemBranchSync()
            val conflictsOf = { project: Project ->
                ConflictResolution(
                    project = project,
                    store = store,
                    recorder = recorder,
                    sessions = sessions,
                    mcpPort = endpoint.port,
                    runtime = runtime,
                    costRecorder = costRecorder,
                    branchSync = branchSync,
                    head = GitWorktreeHead(),
                    configDir = dataDir.resolve("mcp"),
                    clock = clock,
                    model = MODEL,
                    limits = ConflictLimits(),
                    log = log,
                )
            }
            val gateExecutor = GateExecutor(store, recorder, clock, gateRunner, log)
            val validateLauncher = ValidateStageLauncher(
                pipelineFor = { cardId ->
                    val project = projectOf(cardId, store, registry)
                    ValidatePipeline(
                        project = project,
                        store = store,
                        recorder = recorder,
                        worktrees = worktrees,
                        sessions = sessions,
                        mcpPort = endpoint.port,
                        runtime = runtime,
                        costRecorder = costRecorder,
                        branchSync = branchSync,
                        gates = ProjectValidateGates(
                            project, store, recorder, gateExecutor, changedPaths, clock, dataDir.resolve("gates"),
                        ),
                        configDir = dataDir.resolve("mcp"),
                        clock = clock,
                        model = MODEL,
                        delivery = ProjectDelivery.create(project, dataDir.resolve("forge")),
                        changedPaths = changedPaths,
                        conflicts = conflictsOf(project),
                        milestones = milestones,
                        files = files,
                    )
                },
                pool = pool,
                log = log,
                onRunFinished = control::onRunFinished,
            )
            val deliveryTrigger = AutonomousDeliveryTrigger(
                AutonomousDelivery(
                    store = store,
                    recorder = recorder,
                    forge = { cardId -> ProjectDelivery.forgeOf(projectOf(cardId, store, registry), dataDir.resolve("forge")) },
                    policies = { cardId -> projectOf(cardId, store, registry).policies },
                    pause = ParkingCiPause(),
                    maxPolls = DELIVERY_MAX_POLLS,
                    clock = clock,
                    log = log,
                    conflicts = ConflictResolver { cardId, worktree ->
                        conflictsOf(projectOf(cardId, store, registry)).resolve(cardId, worktree)
                    },
                    publisher = SystemBranchPublisher(),
                    worktreeOf = { cardId ->
                        val title = store.card(cardId)?.title ?: cardId
                        worktrees.ensure(projectOf(cardId, store, registry), cardId, title).path
                    },
                ),
                log,
                PullRequestHandoff(
                    store = store,
                    recorder = recorder,
                    forge = { cardId -> ProjectDelivery.forgeOf(projectOf(cardId, store, registry), dataDir.resolve("forge")) },
                    policies = { cardId -> projectOf(cardId, store, registry).policies },
                    clock = clock,
                    log = log,
                ),
            )
            val dispatcher = Dispatcher(
                store = store,
                pool = pool,
                launcher = StageRouter(
                    mapOf(
                        DispatchStage.REFINE to refineLauncher,
                        DispatchStage.DEVELOP to developLauncher,
                        DispatchStage.VALIDATE to validateLauncher,
                    ),
                ),
                pause = FilePauseSwitch(dataDir.resolve("PAUSE")),
                schedule = control,
                limits = DispatchLimits(emptyMap()),
                clock = clock,
                tick = TICK,
                log = log,
                launchGuard = control,
                stages = setOf(DispatchStage.REFINE, DispatchStage.DEVELOP, DispatchStage.VALIDATE),
                deliveries = deliveryTrigger,
            )
            val pulse = FactoryPulse(
                store = store,
                pool = pool,
                poolCapacity = { poolCapacity(settings, log) },
                clock = clock,
                rules = AssistantStateRules(zone = ZoneId.systemDefault()),
                speaker = AssistantSpeaker(null, VoiceMode.OFF, log = log),
            )
            return RefineRuntime(
                dispatcher, endpoint, dispatcherAllowed = prices is Prices.Loaded, stepUp = stepUp, deliveryTrigger = deliveryTrigger,
                milestones = milestones,
                costSummary = CostSummaryQuery(store, control::limits),
                pulse = pulse,
                docsDraft = ProjectDocsDraft(
                    registry = registry,
                    files = files,
                    sessions = sessions,
                    runtime = runtime,
                    ownerProof = commands.ownerProof,
                    scratchRoot = docsScratch,
                    mcpPort = endpoint.port,
                    configDir = dataDir.resolve("mcp"),
                    model = MODEL,
                    log = log,
                ),
            )
        }

        private fun loadPrices(file: Path): Prices {
            val text = try {
                Files.readString(file)
            } catch (e: IOException) {
                val field = if (Files.exists(file)) "$PRICES_FILE.unreadable" else "$PRICES_FILE.missing"
                return Prices.Failed(field)
            }
            return try {
                Prices.Loaded(PriceTable.parse(text))
            } catch (e: InvalidPriceTableException) {
                Prices.Failed("$PRICES_FILE.invalid")
            }
        }

        /** The project of the card's `card_project` link; a card without link falls back only to a sole project. */
        private fun projectOf(cardId: String, store: CardEventStore, registry: ProjectRegistry): Project {
            val projects = registry.list()
            val linked = store.projectOf(cardId)
            if (linked != null) {
                return projects.firstOrNull { it.repo == linked }
                    ?: throw IllegalStateException("card $cardId is linked to a repository that is not registered")
            }
            val only = projects.singleOrNull()
                ?: throw IllegalStateException("card $cardId has no project link and ${projects.size} projects are registered")
            store.assignProject(cardId, only.repo)
            return only
        }
    }
}
