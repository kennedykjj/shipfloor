package shipfloor.daemon

import java.math.BigDecimal

/** Token totals of one model in one transcript. */
data class TokenCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite5m: Long = 0,
    val cacheWrite1h: Long = 0,
) {
    operator fun plus(other: TokenCounts): TokenCounts = TokenCounts(
        input + other.input,
        output + other.output,
        cacheRead + other.cacheRead,
        cacheWrite5m + other.cacheWrite5m,
        cacheWrite1h + other.cacheWrite1h,
    )
}

sealed interface RunCost {
    data class Known(val usd: BigDecimal) : RunCost

    data class Partial(val usd: BigDecimal, val unpricedModels: List<String>) : RunCost

    data class Unknown(val reason: String) : RunCost
}

/** Pure cost from token counts and a price table; never reads a cost the agent or CLI reported. */
object RunCostCalculator {
    private val PER_MILLION = BigDecimal(1_000_000)

    fun cost(usage: Map<String, TokenCounts>, prices: PriceTable): RunCost {
        if (usage.isEmpty()) return RunCost.Unknown("no token usage in transcript")
        var total = BigDecimal.ZERO
        val unpriced = mutableListOf<String>()
        usage.forEach { (model, tokens) ->
            val price = prices.priceOf(model)
            if (price == null) {
                unpriced += model
            } else {
                total += of(tokens.input, price.input) + of(tokens.output, price.output) +
                    of(tokens.cacheRead, price.cacheRead) + of(tokens.cacheWrite5m, price.cacheWrite5m) +
                    of(tokens.cacheWrite1h, price.cacheWrite1h)
            }
        }
        return when {
            unpriced.isEmpty() -> RunCost.Known(total)
            unpriced.size == usage.size -> RunCost.Unknown("no price for models: ${unpriced.joinToString(",")}")
            else -> RunCost.Partial(total, unpriced)
        }
    }

    private fun of(tokens: Long, perMillion: BigDecimal): BigDecimal =
        BigDecimal(tokens).multiply(perMillion).divide(PER_MILLION)
}
