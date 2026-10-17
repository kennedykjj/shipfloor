package shipfloor.daemon

import java.math.BigDecimal
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

class InvalidPriceTableException(message: String) : RuntimeException(message)

/** USD per million tokens for one model; a kind missing from the table costs zero. */
data class ModelPrice(
    val input: BigDecimal = BigDecimal.ZERO,
    val output: BigDecimal = BigDecimal.ZERO,
    val cacheRead: BigDecimal = BigDecimal.ZERO,
    val cacheWrite5m: BigDecimal = BigDecimal.ZERO,
    val cacheWrite1h: BigDecimal = BigDecimal.ZERO,
)

/**
 * Price table read from `prices.yml`: model name to per-million prices, either as top-level keys (the V0 file) or
 * under a `models:` map.
 */
class PriceTable private constructor(private val prices: Map<String, ModelPrice>) {

    fun priceOf(model: String): ModelPrice? = prices[model]

    companion object {
        private const val MAX_CHARS = 1_000_000
        private const val MAX_ALIASES = 10

        fun parse(yaml: String): PriceTable {
            val root = try {
                Load(LoadSettings.builder().setCodePointLimit(MAX_CHARS).setMaxAliasesForCollections(MAX_ALIASES).build())
                    .loadFromString(yaml)
            } catch (e: YamlEngineException) {
                throw InvalidPriceTableException("prices.yml is not valid YAML")
            }
            val top = root as? Map<*, *> ?: throw InvalidPriceTableException("prices.yml must be a map of models")
            // The nested form wins only when `models` holds a map of maps; otherwise the model names are the top-level keys.
            val nested = top["models"] as? Map<*, *>
            val models = if (nested != null && nested.values.all { it is Map<*, *> }) nested else top
            val parsed = models.entries.associate { (name, raw) ->
                val key = name as? String ?: throw InvalidPriceTableException("model name must be text")
                val fields = raw as? Map<*, *> ?: throw InvalidPriceTableException("prices of model $key must be a map")
                key to ModelPrice(
                    input = amount(key, fields, "input"),
                    output = amount(key, fields, "output"),
                    cacheRead = amount(key, fields, "cache_read"),
                    cacheWrite5m = amount(key, fields, "cache_write_5m"),
                    cacheWrite1h = amount(key, fields, "cache_write_1h"),
                )
            }
            return PriceTable(parsed)
        }

        private fun amount(model: String, fields: Map<*, *>, kind: String): BigDecimal {
            val raw = fields[kind] ?: return BigDecimal.ZERO
            val value = when (raw) {
                is Number -> raw.toString()
                is String -> raw
                else -> throw InvalidPriceTableException("price $kind of model $model must be a number")
            }
            val number = try {
                BigDecimal(value)
            } catch (e: NumberFormatException) {
                throw InvalidPriceTableException("price $kind of model $model must be a number")
            }
            if (number.signum() < 0) throw InvalidPriceTableException("price $kind of model $model must not be negative")
            return number
        }
    }
}
