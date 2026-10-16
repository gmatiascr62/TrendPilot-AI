package com.trendpilot.core.universe

import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.Ticker24h
import java.math.BigDecimal

/** One row of an external market-cap ranking (CoinGecko /coins/markets). */
data class MarketCapEntry(val id: String, val symbol: String, val name: String, val marketCapUsd: Double, val rank: Int, val priceUsd: Double)

data class UniverseResult(val selected: List<String>, val excluded: List<Pair<String, String>>, val generatedAtMs: Long, val source: String)

/**
 * Picks the top-N assets by EXTERNAL market cap that have a tradable Spot USDT pair on Binance.
 * The Binance volume ranking is never used as a market-cap proxy. Leaving the list does not
 * force selling an open position.
 */
object UniverseSelector {
    val STABLE_OR_PEGGED = setOf(
        "USDT", "USDC", "DAI", "FDUSD", "TUSD", "BUSD", "USDP", "USDD", "PYUSD", "USDE", "USDS", "EURC", "EURI", "AEUR",
        "FRAX", "LUSD", "GUSD", "USD1", "RLUSD", "BFUSD", "USDF", "USDX", "XUSD", "SUSD", "CRVUSD", "GHO", "XAUT", "PAXG",
    )
    val WRAPPED_OR_STAKED = setOf(
        "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "CBBTC", "BETH", "WBETH", "WBNB", "LBTC", "SOLVBTC", "JITOSOL",
        "RETH", "BSOL", "METH", "EZETH", "BNSOL", "CBETH", "TBTC", "MSOL", "RSETH",
    )
    private val LEVERAGED = Regex(".+(UP|DOWN|BULL|BEAR)$")

    fun select(
        caps: List<MarketCapEntry>,
        rules: Map<String, SymbolRules>,
        tickers: Map<String, Ticker24h>,
        nowMs: Long,
        size: Int = 20,
        minQuoteVolume: BigDecimal = BigDecimal("20000000"),
        blocked: Set<String> = emptySet(),
        quote: String = "USDT",
    ): UniverseResult {
        val selected = mutableListOf<String>()
        val excluded = mutableListOf<Pair<String, String>>()
        val seenBases = mutableSetOf<String>()
        for (c in caps.sortedBy { it.rank }) {
            if (selected.size >= size) break
            val base = c.symbol.uppercase()
            val pair = base + quote
            val why = when {
                base in STABLE_OR_PEGGED -> "stablecoin o activo respaldado"
                base in WRAPPED_OR_STAKED -> "token envuelto o de staking"
                c.name.contains("usd", ignoreCase = true) && c.priceUsd in 0.97..1.03 -> "probable stablecoin"
                LEVERAGED.matches(base) && base.length > 4 -> "token apalancado"
                !seenBases.add(base) -> "símbolo duplicado en el ranking (colisión de ticker)"
                pair in blocked || base in blocked -> "bloqueado por el usuario"
                rules[pair] == null -> "sin par $pair en Binance Spot"
                rules[pair]?.isTrading != true -> "par $pair no operable (${rules[pair]?.status})"
                rules[pair]?.protectiveStopType() == null -> "$pair no admite órdenes stop"
                (tickers[pair]?.quoteVolume ?: BigDecimal.ZERO) < minQuoteVolume -> "liquidez insuficiente (volumen 24h < $minQuoteVolume $quote)"
                tickers[pair]?.let { t -> c.priceUsd > 0 && kotlin.math.abs(t.lastPrice.toDouble() / c.priceUsd - 1) > 0.05 } == true ->
                    "precio Binance no coincide con la fuente externa (posible otro activo con el mismo ticker)"
                else -> null
            }
            if (why == null) selected += pair else excluded += pair to why
        }
        return UniverseResult(selected, excluded, nowMs, "CoinGecko /coins/markets (market_cap_desc)")
    }
}
