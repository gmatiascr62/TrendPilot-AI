package com.trendpilot.core.filters

import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Trading rules for one symbol, taken from GET /api/v3/exchangeInfo.
 * See https://github.com/binance/binance-spot-api-docs/blob/master/filters.md
 * A `null` filter means the exchange did not send it (the rule does not apply).
 */
data class SymbolRules(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val status: String,
    val isSpotTradingAllowed: Boolean,
    val orderTypes: Set<String>,
    val otoAllowed: Boolean = false,
    val ocoAllowed: Boolean = false,
    val cancelReplaceAllowed: Boolean = false,
    val amendAllowed: Boolean = false,
    val allowTrailingStop: Boolean = false,
    val priceFilter: PriceFilter? = null,
    val lotSize: LotSize? = null,
    val marketLotSize: LotSize? = null,
    val notional: Notional? = null,
    val percentPriceBySide: PercentPriceBySide? = null,
    val maxNumOrders: Int? = null,
    val maxNumAlgoOrders: Int? = null,
) {
    val isTrading: Boolean get() = status == "TRADING" && isSpotTradingAllowed

    fun supports(type: OrderType) = type.name in orderTypes

    /** Preferred protective stop type: STOP_LOSS (market on trigger) guarantees execution, not price. */
    fun protectiveStopType(): OrderType? = when {
        supports(OrderType.STOP_LOSS) -> OrderType.STOP_LOSS
        supports(OrderType.STOP_LOSS_LIMIT) -> OrderType.STOP_LOSS_LIMIT
        else -> null
    }
}

data class PriceFilter(val minPrice: BigDecimal, val maxPrice: BigDecimal, val tickSize: BigDecimal)
data class LotSize(val minQty: BigDecimal, val maxQty: BigDecimal, val stepSize: BigDecimal)

/** NOTIONAL filter (MIN_NOTIONAL is mapped onto this with maxNotional = null). */
data class Notional(
    val minNotional: BigDecimal,
    val applyMinToMarket: Boolean,
    val maxNotional: BigDecimal?,
    val applyMaxToMarket: Boolean,
)

data class PercentPriceBySide(
    val bidMultiplierUp: BigDecimal,
    val bidMultiplierDown: BigDecimal,
    val askMultiplierUp: BigDecimal,
    val askMultiplierDown: BigDecimal,
)

/** Pure helpers implementing the Binance filter arithmetic with exact decimals. */
object FilterMath {
    fun floorToStep(value: BigDecimal, step: BigDecimal): BigDecimal {
        if (step.signum() == 0) return value
        val steps = value.divide(step, 0, RoundingMode.FLOOR)
        return steps.multiply(step).stripTrailingZerosSafe(step)
    }

    fun ceilToStep(value: BigDecimal, step: BigDecimal): BigDecimal {
        if (step.signum() == 0) return value
        val steps = value.divide(step, 0, RoundingMode.CEILING)
        return steps.multiply(step).stripTrailingZerosSafe(step)
    }

    fun isMultiple(value: BigDecimal, step: BigDecimal): Boolean =
        step.signum() == 0 || value.remainder(step).signum() == 0

    private fun BigDecimal.stripTrailingZerosSafe(step: BigDecimal): BigDecimal =
        setScale(maxOf(step.stripTrailingZeros().scale(), 0), RoundingMode.UNNECESSARY)
}

data class FilterViolation(val filter: String, val detail: String)

/**
 * Validates an order against the symbol filters *before* it is sent.
 * [referencePrice] is used for MARKET notional checks (the exchange uses an average price;
 * callers should pass a conservative value).
 */
object FilterValidator {
    fun validate(
        rules: SymbolRules,
        type: OrderType,
        side: Side,
        quantity: BigDecimal,
        price: BigDecimal?,
        stopPrice: BigDecimal?,
        referencePrice: BigDecimal,
    ): List<FilterViolation> {
        val v = mutableListOf<FilterViolation>()
        if (!rules.isTrading) v += FilterViolation("STATUS", "${rules.symbol} status=${rules.status}")
        if (!rules.supports(type)) v += FilterViolation("ORDER_TYPES", "$type not allowed on ${rules.symbol}")
        if (quantity.signum() <= 0) v += FilterViolation("QUANTITY", "quantity must be > 0")

        val lot = if (type == OrderType.MARKET) rules.marketLotSize?.takeIf { it.stepSize.signum() > 0 } ?: rules.lotSize else rules.lotSize
        // STOP_LOSS becomes a MARKET order when triggered, so MARKET_LOT_SIZE applies too.
        val lots = listOfNotNull(lot, if (type == OrderType.STOP_LOSS) rules.marketLotSize else null)
        for (l in lots) {
            if (quantity < l.minQty) v += FilterViolation("LOT_SIZE", "qty $quantity < minQty ${l.minQty}")
            if (l.maxQty.signum() > 0 && quantity > l.maxQty) v += FilterViolation("LOT_SIZE", "qty $quantity > maxQty ${l.maxQty}")
            if (!FilterMath.isMultiple(quantity, l.stepSize)) v += FilterViolation("LOT_SIZE", "qty $quantity not multiple of ${l.stepSize}")
        }

        rules.priceFilter?.let { pf ->
            for ((name, p) in listOf("price" to price, "stopPrice" to stopPrice)) {
                if (p == null) continue
                if (pf.minPrice.signum() > 0 && p < pf.minPrice) v += FilterViolation("PRICE_FILTER", "$name $p < ${pf.minPrice}")
                if (pf.maxPrice.signum() > 0 && p > pf.maxPrice) v += FilterViolation("PRICE_FILTER", "$name $p > ${pf.maxPrice}")
                if (!FilterMath.isMultiple(p, pf.tickSize)) v += FilterViolation("PRICE_FILTER", "$name $p not multiple of ${pf.tickSize}")
            }
        }

        rules.notional?.let { n ->
            val isMarketLike = type == OrderType.MARKET || type == OrderType.STOP_LOSS
            // For stops the notional that matters is at the trigger/limit price.
            val notionalPrice = price ?: stopPrice ?: referencePrice
            val notional = quantity * notionalPrice
            // Conservative: minNotional is enforced even when applyMinToMarket=false, because a
            // position we cannot later protect or exit with a valid order must never be opened.
            if (notional < n.minNotional) v += FilterViolation("NOTIONAL", "notional $notional < ${n.minNotional}")
            if (n.maxNotional != null && n.maxNotional.signum() > 0 && (!isMarketLike || n.applyMaxToMarket) && notional > n.maxNotional) {
                v += FilterViolation("NOTIONAL", "notional $notional > ${n.maxNotional}")
            }
        }

        rules.percentPriceBySide?.let { pp ->
            if (price != null && (type == OrderType.LIMIT || type == OrderType.STOP_LOSS_LIMIT)) {
                val p = price
                val (up, down) = if (side == Side.BUY) pp.bidMultiplierUp to pp.bidMultiplierDown else pp.askMultiplierUp to pp.askMultiplierDown
                if (p > referencePrice * up) v += FilterViolation("PERCENT_PRICE_BY_SIDE", "price $p > ref*$up")
                if (p < referencePrice * down) v += FilterViolation("PERCENT_PRICE_BY_SIDE", "price $p < ref*$down")
            }
        }
        return v.distinct()
    }
}
