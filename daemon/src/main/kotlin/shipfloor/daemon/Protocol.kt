package shipfloor.daemon

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shipfloor.core.Actor
import shipfloor.core.AssistantState
import shipfloor.core.Column
import shipfloor.core.ColumnTime
import shipfloor.core.FactorySettings
import shipfloor.core.QueueForecast
import shipfloor.store.StoredEvent

/** A parsed client message. */
sealed interface Request {
    data object Ping : Request

    data class CardCreate(val actor: Actor, val title: String, val proof: String? = null) : Request {
        override fun toString(): String = "CardCreate(actor=$actor, title=$title, proof=${if (proof == null) "null" else "***"})"
    }

    data class CardMove(val actor: Actor, val id: String, val to: Column, val proof: String? = null) : Request {
        override fun toString(): String = "CardMove(actor=$actor, id=$id, to=$to, proof=${if (proof == null) "null" else "***"})"
    }

    data class CardApprove(val actor: Actor, val id: String, val proof: String? = null) : Request {
        override fun toString(): String = "CardApprove(actor=$actor, id=$id, proof=${if (proof == null) "null" else "***"})"
    }

    /** Owner release of a card with an open block; the column does not change. */
    data class CardUnblock(val actor: Actor, val id: String, val proof: String? = null) : Request {
        override fun toString(): String = "CardUnblock(actor=$actor, id=$id, proof=${if (proof == null) "null" else "***"})"
    }

    data class CardRequestChange(val actor: Actor, val id: String, val note: String? = null, val proof: String? = null) : Request {
        override fun toString(): String =
            "CardRequestChange(actor=$actor, id=$id, note=${if (note == null) "null" else "***"}, proof=${if (proof == null) "null" else "***"})"
    }

    data class ProjectAdd(val actor: Actor, val path: String, val proof: String? = null) : Request {
        override fun toString(): String = "ProjectAdd(actor=$actor, path=$path, proof=${if (proof == null) "null" else "***"})"
    }

    data object ProjectList : Request

    /** Owner request for a draft of the project documents missing on main; [project] null means the only registered project. */
    data class ProjectDocsDraft(val actor: Actor, val project: String? = null, val proof: String? = null) : Request {
        override fun toString(): String = "ProjectDocsDraft(actor=$actor, project=$project, proof=${if (proof == null) "null" else "***"})"
    }

    /** Recreates the cards of [project] (null: the only registered project) from its pull requests. */
    data class CardRebuild(val actor: Actor, val project: String? = null, val proof: String? = null) : Request {
        override fun toString(): String = "CardRebuild(actor=$actor, project=$project, proof=${if (proof == null) "null" else "***"})"
    }

    /** Imports an issue by [id] or the results of a [query] (exactly one) from the tracker of [project]. */
    data class CardImport(
        val actor: Actor,
        val project: String? = null,
        val id: String? = null,
        val query: String? = null,
        val proof: String? = null,
    ) : Request {
        override fun toString(): String =
            "CardImport(actor=$actor, project=$project, id=$id, query=${if (query == null) "null" else "***"}, proof=${if (proof == null) "null" else "***"})"
    }

    /** Read-only: the closure draft of a Done card. */
    data class CardCloseDraft(val id: String) : Request

    /** Owner confirmation of the closure draft whose hash is [draftHash]; also the retry of a failed write. */
    data class CardCloseConfirm(val actor: Actor, val id: String, val draftHash: String, val proof: String? = null) : Request {
        override fun toString(): String = "CardCloseConfirm(actor=$actor, id=$id, draftHash=$draftHash, proof=${if (proof == null) "null" else "***"})"
    }

    /** Imports the V0 board [rows] (issue id and V0 column name) plus the open issues, from the tracker of [project]. */
    data class CardImportBoard(
        val actor: Actor,
        val project: String? = null,
        val rows: List<BoardRow> = emptyList(),
        val proof: String? = null,
    ) : Request {
        override fun toString(): String =
            "CardImportBoard(actor=$actor, project=$project, rows=${rows.size}, proof=${if (proof == null) "null" else "***"})"
    }

    /** Read-only: the milestones held until the owner confirms them. */
    data object MilestoneList : Request

    /** Owner confirmation of the pending [milestone] of card [id] whose text hash is [textHash]; also the retry of a failed write. */
    data class MilestoneConfirm(
        val actor: Actor,
        val id: String,
        val milestone: String,
        val textHash: String,
        val proof: String? = null,
    ) : Request {
        override fun toString(): String =
            "MilestoneConfirm(actor=$actor, id=$id, milestone=$milestone, textHash=$textHash, proof=${if (proof == null) "null" else "***"})"
    }

    /** Read-only: the factory settings in force. */
    data object SettingsGet : Request

    /** Owner save of the factory [settings]. */
    data class SettingsSave(val actor: Actor, val settings: FactorySettings, val proof: String? = null) : Request {
        override fun toString(): String = "SettingsSave(actor=$actor, settings=***, proof=${if (proof == null) "null" else "***"})"
    }

    data object CardList : Request
    data class CardShow(val id: String) : Request

    /** Read-only: card, spec, events and runs of card [id]. */
    data class CardDetail(val id: String) : Request

    /** Read-only: lead time per stage and estimated cost of every Done card, as CSV. */
    data object ReportCsv : Request

    /** Read-only: the audit trail of card [id]; [format] is validated by the handler. */
    data class AuditExport(val id: String, val format: String) : Request

    /** Read-only: the cost recorded in [from, to), the configured limits and which totals reached them; days are in [zone]. */
    data class CostSummary(val from: Instant, val to: Instant, val zone: ZoneId = ZoneOffset.UTC) : Request

    /** Read-only: the time per column of the stays that ended in [from, to). */
    data class ColumnTimes(val from: Instant, val to: Instant) : Request

    /** Read-only: the newest [limit] events (1..[MAX_RECENT_EVENTS]) in seq order. */
    data class EventsRecent(val limit: Int) : Request

    /** Read-only: the assistant state and phrase, the pool capacity and the cards with an active run. Takes no parameter. */
    data object FactoryPulse : Request

    /** Starts the event stream; [after] is the last seq the client has seen, or null for live events only. */
    data class Subscribe(val after: Long?) : Request

    /** The line is not a valid request; [message] is safe to send back (never echoes the input). */
    data class Rejected(val message: String) : Request
}

/** A card as the client sees it. */
data class CardView(
    val id: String,
    val title: String,
    val column: Column,
    val blocked: Boolean = false,
    val enteredAt: Instant? = null,
    val cost: CardListCost? = null,
)

/** The total cost of a card in `card.list`: [costUsd] is present only for [CostStatus.KNOWN] and [CostStatus.PARTIAL]. */
data class CardListCost(val status: CostStatus, val costUsd: BigDecimal? = null)

/** One line of the V0 board: the issue number and the V0 column name. */
data class BoardRow(val id: String, val column: String)

/** A milestone waiting for the owner: the [text] to be written to the tracker and its [textHash]. */
data class MilestoneItem(val card: String, val milestone: String, val text: String, val textHash: String)

/** A board row that was not imported; [reason] is `unknown_column` or `not_found`. */
data class IgnoredRow(val id: String, val reason: String)

/** The cost of one card in the period asked. */
data class CardCost(val card: String, val costUsd: BigDecimal, val status: CostStatus)

/** The cost of one stage (`unknown` when the event has none) in the period asked. */
data class StageCost(val stage: String, val costUsd: BigDecimal, val status: CostStatus)

/** The cost of one local day (`AAAA-MM-DD`) in the period asked. */
data class DayCost(val day: String, val costUsd: BigDecimal, val status: CostStatus)

/** The draft of one project document: [status] is `drafted` (with [text]) or `failed` (with [reason]). */
data class DocDraft(val doc: String, val status: String, val text: String? = null, val reason: String? = null)

/** A reply to the client. */
sealed interface Response {
    /** The newest events in seq order; [truncated] is true when older ones exist beyond the window. */
    data class EventsRecentResult(val events: List<StoredEvent>, val truncated: Boolean) : Response

    /** The time of the columns that have a statistic; the others are absent, never zero. */
    data class ColumnTimesResult(val times: Map<Column, ColumnTime>) : Response

    /** The pulse of the factory; [active] holds card labels (`#<number>` or 8 hex characters), never titles. */
    data class PulseResult(
        val state: AssistantState,
        val phrase: String,
        val poolCapacity: Int,
        val active: List<String>,
        val forecast: QueueForecast,
    ) : Response

    data class CostSummaryResult(
        val totalUsd: BigDecimal,
        val status: CostStatus,
        val cards: List<CardCost>,
        val limits: CostLimits,
        val dayReached: Boolean,
        val cardsReached: List<String>,
        val stages: List<StageCost> = emptyList(),
        val days: List<DayCost> = emptyList(),
    ) : Response

    data object Pong : Response
    data class CardResult(val card: CardView) : Response
    data class CardDetailResult(val detail: JsonObject) : Response
    data class CardsResult(val cards: List<CardView>) : Response
    data class BoardImportResult(val cards: List<CardView>, val ignored: List<IgnoredRow>) : Response
    data class ReportResult(val csv: String) : Response
    data class AuditResult(val text: String) : Response
    data class ProjectResult(val project: Project) : Response
    data class ProjectsResult(val projects: List<Project>) : Response

    /** One draft per missing document; [DocDraft.text] is set when drafted, [DocDraft.reason] when failed. */
    data class DocsDraftResult(val drafts: List<DocDraft>) : Response
    data class CloseDraftResult(val status: String, val summary: String, val prLink: String, val draftHash: String) : Response
    data class MilestonesResult(val items: List<MilestoneItem>) : Response
    data class Settings(val settings: FactorySettings) : Response
    data class Error(val message: String) : Response
}

/** Parses one protocol line. Pure: no IO. */
fun parseRequest(line: String): Request {
    if (line.isBlank()) return Request.Rejected("message must be a JSON object")
    val element = try {
        Json.parseToJsonElement(line)
    } catch (e: SerializationException) {
        return Request.Rejected("invalid JSON")
    }
    // kotlinx accepts unquoted text as a primitive literal; strict JSON does not.
    if (element is JsonPrimitive && !element.isString && !isJsonLiteral(element.content)) {
        return Request.Rejected("invalid JSON")
    }
    val obj = element as? JsonObject ?: return Request.Rejected("message must be a JSON object")
    val cmd = obj["cmd"] as? JsonPrimitive
    if (cmd == null || !cmd.isString) return Request.Rejected("missing cmd")
    return when (cmd.content) {
        "ping" -> Request.Ping
        "card.create" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val title = stringField(obj, "title") ?: return Request.Rejected("missing title")
            Request.CardCreate(actor, title, stringField(obj, "proof"))
        }
        "card.move" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            val to = stringField(obj, "to") ?: return Request.Rejected("missing to")
            val column = Column.entries.firstOrNull { it.name == to } ?: return Request.Rejected("unknown column")
            Request.CardMove(actor, id, column, stringField(obj, "proof"))
        }
        "card.approve" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.CardApprove(actor, id, stringField(obj, "proof"))
        }
        "card.unblock" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.CardUnblock(actor, id, stringField(obj, "proof"))
        }
        "card.request-change" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.CardRequestChange(actor, id, stringField(obj, "note"), stringField(obj, "proof"))
        }
        "project.add" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val path = stringField(obj, "path") ?: return Request.Rejected("missing path")
            Request.ProjectAdd(actor, path, stringField(obj, "proof"))
        }
        "project.docs.draft" -> {
            // An actor that is not even parseable is still not the owner: same refusal, before anything is read.
            val actor = parseActor(obj) ?: return Request.Rejected(ProjectDocsDraft.OWNER_ONLY)
            Request.ProjectDocsDraft(actor, stringField(obj, "project"), stringField(obj, "proof"))
        }
        "card.rebuild" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            Request.CardRebuild(actor, stringField(obj, "project"), stringField(obj, "proof"))
        }
        "card.import" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            Request.CardImport(actor, stringField(obj, "project"), stringField(obj, "id"), stringField(obj, "query"), stringField(obj, "proof"))
        }
        "card.close.draft" -> {
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.CardCloseDraft(id)
        }
        "card.close.confirm" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            val draftHash = stringField(obj, "draftHash") ?: return Request.Rejected("missing draftHash")
            Request.CardCloseConfirm(actor, id, draftHash, stringField(obj, "proof"))
        }
        "card.import_board" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val rows = parseRows(obj) ?: return Request.Rejected("missing or invalid rows")
            Request.CardImportBoard(actor, stringField(obj, "project"), rows, stringField(obj, "proof"))
        }
        "milestone.list" -> Request.MilestoneList
        "milestone.confirm" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            val milestone = stringField(obj, "milestone") ?: return Request.Rejected("missing milestone")
            val textHash = stringField(obj, "textHash") ?: return Request.Rejected("missing textHash")
            Request.MilestoneConfirm(actor, id, milestone, textHash, stringField(obj, "proof"))
        }
        "settings.get" -> Request.SettingsGet
        "settings.save" -> {
            val actor = parseActor(obj) ?: return Request.Rejected("missing or invalid actor")
            val body = obj["settings"] as? JsonObject ?: return Request.Rejected("settings: missing or not an object")
            val settings = try {
                settingsFromJson(body)
            } catch (e: InvalidSettingsJsonException) {
                return Request.Rejected("${e.field}: ${e.message}")
            }
            Request.SettingsSave(actor, settings, stringField(obj, "proof"))
        }
        "project.list" -> Request.ProjectList
        "card.list" -> Request.CardList
        "card.show" -> {
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.CardShow(id)
        }
        "card.detail" -> {
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.CardDetail(id)
        }
        "report.csv" -> Request.ReportCsv
        "audit.export" -> {
            val id = stringField(obj, "id") ?: return Request.Rejected("missing id")
            Request.AuditExport(id, stringField(obj, "format") ?: "json")
        }
        "events.subscribe" -> parseSubscribe(obj)
        "events.recent" -> parseRecent(obj)
        "factory.pulse" -> Request.FactoryPulse
        "cost.summary" -> {
            val from = instantField(obj, "from") ?: return Request.Rejected("missing or invalid from")
            val to = instantField(obj, "to") ?: return Request.Rejected("missing or invalid to")
            val zone = if (obj["zone"] == null) ZoneOffset.UTC else zoneField(obj, "zone") ?: return Request.Rejected("invalid zone")
            Request.CostSummary(from, to, zone)
        }
        "column.times" -> {
            val from = instantField(obj, "from") ?: return Request.Rejected("missing or invalid from")
            val to = instantField(obj, "to") ?: return Request.Rejected("missing or invalid to")
            Request.ColumnTimes(from, to)
        }
        else -> Request.Rejected("unknown cmd")
    }
}

private fun parseSubscribe(obj: JsonObject): Request {
    val element = obj["after"] ?: return Request.Subscribe(null)
    val primitive = element as? JsonPrimitive
    val after = if (primitive == null || primitive.isString) null else primitive.content.toLongOrNull()
    if (after == null || after < 0) return Request.Rejected("invalid after")
    return Request.Subscribe(after)
}

/** Largest window `events.recent` returns. */
const val MAX_RECENT_EVENTS = 2000

private fun parseRecent(obj: JsonObject): Request {
    val primitive = obj["limit"] as? JsonPrimitive
    val limit = if (primitive == null || primitive.isString) null else primitive.content.toIntOrNull()
    if (limit == null || limit < 1 || limit > MAX_RECENT_EVENTS) return Request.Rejected("invalid limit")
    return Request.EventsRecent(limit)
}

private fun parseRows(obj: JsonObject): List<BoardRow>? {
    val array = obj["rows"] as? JsonArray ?: return null
    return array.map { element ->
        val row = element as? JsonObject ?: return null
        BoardRow(stringField(row, "id") ?: return null, stringField(row, "column") ?: return null)
    }
}

private fun instantField(obj: JsonObject, key: String): Instant? =
    stringField(obj, key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun zoneField(obj: JsonObject, key: String): ZoneId? =
    stringField(obj, key)?.let {
        try {
            ZoneId.of(it)
        } catch (e: DateTimeException) {
            null
        }
    }

private fun stringField(obj: JsonObject, key: String): String? {
    val primitive = obj[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

/** Accepts `owner`, `agent:<role>` and `factory:<component>`. */
private fun parseActor(obj: JsonObject): Actor? {
    val text = stringField(obj, "actor") ?: return null
    if (text == "owner") return Actor.Owner
    val kind = text.substringBefore(':')
    val name = text.substringAfter(':', "")
    if (name.isBlank()) return null
    return when (kind) {
        "agent" -> Actor.Agent(name)
        "factory" -> Actor.Factory(name)
        else -> null
    }
}

private fun isJsonLiteral(text: String): Boolean =
    text == "null" || text == "true" || text == "false" || text.toDoubleOrNull() != null

private fun forecastJson(forecast: QueueForecast): JsonObject = buildJsonObject {
    when (forecast) {
        QueueForecast.Empty -> put("kind", "EMPTY")
        is QueueForecast.FinishesAt -> {
            put("kind", "FINISHES_AT")
            put("at", forecast.at.toString())
            put("ownerSteps", forecast.ownerSteps)
        }
        is QueueForecast.Unknown -> {
            put("kind", "UNKNOWN")
            put("gap", forecast.gap.name)
        }
    }
}

private fun cardJson(card: CardView): JsonObject = buildJsonObject {
    put("id", card.id)
    put("title", card.title)
    put("column", card.column.name)
    put("blocked", card.blocked)
    card.enteredAt?.let { put("enteredAt", it.toString()) }
    card.cost?.let { cost ->
        put(
            "cost",
            buildJsonObject {
                put("status", cost.status.name)
                cost.costUsd?.let { put("costUsd", it.toPlainString()) }
            },
        )
    }
}

private fun projectJson(project: Project): JsonObject = buildJsonObject {
    put("name", project.name)
    put("repo", project.repo)
    put(
        "layers",
        JsonArray(
            project.layers.map { layer ->
                buildJsonObject {
                    put("name", layer.name)
                    put("path", layer.path)
                }
            },
        ),
    )
    put(
        "policies",
        buildJsonObject {
            put("delivery_mode", project.policies.deliveryMode.key)
            put("dor", buildJsonArray { project.policies.dor.forEach { add(JsonPrimitive(it.key)) } })
            put(
                "signers",
                buildJsonObject { project.policies.signers.forEach { (effect, signer) -> put(effect.key, signer.key) } },
            )
            put(
                "conventions",
                buildJsonObject {
                    put("commit_format", project.policies.conventions.commitFormat)
                    put("branch_format", project.policies.conventions.branchFormat)
                    put("pr_template", project.policies.conventions.prTemplate)
                    put("language", project.policies.conventions.language)
                },
            )
        },
    )
}

internal fun actorText(actor: Actor): String = when (actor) {
    Actor.Owner -> "owner"
    is Actor.Agent -> "agent:${actor.role}"
    is Actor.Adapter -> "adapter:${actor.provider}"
    is Actor.Factory -> "factory:${actor.component}"
}

/** Renders a stream event as a single JSON line (without the newline). Pure: no IO. */
fun renderEvent(stored: StoredEvent): String = buildJsonObject { put("event", eventJson(stored)) }.toString()

private fun eventJson(stored: StoredEvent): JsonObject = buildJsonObject {
    put("seq", stored.seq)
    put("card", stored.cardId)
    put("id", stored.event.id.value)
    put("at", stored.event.at.toString())
    put("actor", actorText(stored.event.actor))
    put("type", stored.event.type.name)
    put("payload", JsonObject(stored.event.payload.mapValues { JsonPrimitive(it.value) }))
}

/** Renders the line that ends a stream abnormally. Pure: no IO. */
fun renderStreamError(message: String): String = buildJsonObject { put("error", message) }.toString()

/** Renders a response as a single JSON line (without the newline). Pure: no IO. */
fun render(response: Response): String = when (response) {
    Response.Pong -> buildJsonObject {
        put("ok", true)
        put("result", "pong")
    }.toString()
    is Response.CardResult -> buildJsonObject {
        put("ok", true)
        put("result", cardJson(response.card))
    }.toString()
    is Response.CardDetailResult -> buildJsonObject {
        put("ok", true)
        put("detail", response.detail)
    }.toString()
    is Response.CardsResult -> buildJsonObject {
        put("ok", true)
        put("result", JsonArray(response.cards.map { cardJson(it) }))
    }.toString()
    is Response.BoardImportResult -> buildJsonObject {
        put("ok", true)
        put("result", JsonArray(response.cards.map { cardJson(it) }))
        put(
            "ignored",
            JsonArray(
                response.ignored.map { row ->
                    buildJsonObject {
                        put("id", row.id)
                        put("reason", row.reason)
                    }
                },
            ),
        )
    }.toString()
    is Response.ReportResult -> buildJsonObject {
        put("ok", true)
        put("csv", response.csv)
    }.toString()
    is Response.AuditResult -> buildJsonObject {
        put("ok", true)
        put("text", response.text)
    }.toString()
    is Response.ProjectResult -> buildJsonObject {
        put("ok", true)
        put("result", projectJson(response.project))
    }.toString()
    is Response.ProjectsResult -> buildJsonObject {
        put("ok", true)
        put("result", JsonArray(response.projects.map { projectJson(it) }))
    }.toString()
    is Response.DocsDraftResult -> buildJsonObject {
        put("ok", true)
        put(
            "result",
            JsonArray(
                response.drafts.map { draft ->
                    buildJsonObject {
                        put("doc", draft.doc)
                        put("status", draft.status)
                        draft.text?.let { put("text", it) }
                        draft.reason?.let { put("reason", it) }
                    }
                },
            ),
        )
    }.toString()
    is Response.CloseDraftResult -> buildJsonObject {
        put("ok", true)
        put(
            "result",
            buildJsonObject {
                put("status", response.status)
                put("summary", response.summary)
                put("prLink", response.prLink)
                put("draftHash", response.draftHash)
            },
        )
    }.toString()
    is Response.MilestonesResult -> buildJsonObject {
        put("ok", true)
        put(
            "result",
            JsonArray(
                response.items.map { item ->
                    buildJsonObject {
                        put("card", item.card)
                        put("milestone", item.milestone)
                        put("text", item.text)
                        put("textHash", item.textHash)
                    }
                },
            ),
        )
    }.toString()
    is Response.Settings -> buildJsonObject {
        put("ok", true)
        put("result", settingsToJson(response.settings))
    }.toString()
    is Response.EventsRecentResult -> buildJsonObject {
        put("ok", true)
        put("result", JsonArray(response.events.map { eventJson(it) }))
        put("truncated", response.truncated)
    }.toString()
    is Response.ColumnTimesResult -> buildJsonObject {
        put("ok", true)
        put(
            "result",
            buildJsonObject {
                put(
                    "columns",
                    JsonArray(
                        Column.entries.mapNotNull { column ->
                            response.times[column]?.let { time ->
                                buildJsonObject {
                                    put("column", column.name)
                                    put("kind", time.kind.name)
                                    put("seconds", time.seconds)
                                    put("stays", time.stays)
                                }
                            }
                        },
                    ),
                )
            },
        )
    }.toString()
    is Response.PulseResult -> buildJsonObject {
        put("ok", true)
        put(
            "result",
            buildJsonObject {
                put("state", response.state.name)
                put("phrase", response.phrase)
                put("poolCapacity", response.poolCapacity)
                put("active", JsonArray(response.active.map { JsonPrimitive(it) }))
                put("forecast", forecastJson(response.forecast))
            },
        )
    }.toString()
    is Response.CostSummaryResult -> buildJsonObject {
        put("ok", true)
        put(
            "result",
            buildJsonObject {
                put("totalUsd", response.totalUsd.toPlainString())
                put("status", response.status.name)
                put(
                    "cards",
                    JsonArray(
                        response.cards.map { cost ->
                            buildJsonObject {
                                put("card", cost.card)
                                put("costUsd", cost.costUsd.toPlainString())
                                put("status", cost.status.name)
                            }
                        },
                    ),
                )
                put(
                    "limits",
                    buildJsonObject {
                        put("perCardUsd", response.limits.perCardUsd?.toPlainString())
                        put("perDayUsd", response.limits.perDayUsd?.toPlainString())
                    },
                )
                put("dayReached", response.dayReached)
                put("cardsReached", JsonArray(response.cardsReached.map { JsonPrimitive(it) }))
                put(
                    "stages",
                    JsonArray(
                        response.stages.map { cost ->
                            buildJsonObject {
                                put("stage", cost.stage)
                                put("costUsd", cost.costUsd.toPlainString())
                                put("status", cost.status.name)
                            }
                        },
                    ),
                )
                put(
                    "days",
                    JsonArray(
                        response.days.map { cost ->
                            buildJsonObject {
                                put("day", cost.day)
                                put("costUsd", cost.costUsd.toPlainString())
                                put("status", cost.status.name)
                            }
                        },
                    ),
                )
            },
        )
    }.toString()
    is Response.Error -> buildJsonObject {
        put("ok", false)
        put("error", response.message)
    }.toString()
}
