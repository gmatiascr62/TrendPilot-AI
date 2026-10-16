package com.trendpilot.app.engine

import com.trendpilot.app.data.AppDatabase
import com.trendpilot.app.data.EquityEntity
import com.trendpilot.app.data.EventEntity
import com.trendpilot.app.data.KvEngineStateStore
import com.trendpilot.app.data.KvEntity
import com.trendpilot.app.data.PaperStateCodec
import com.trendpilot.app.data.RoomDecisionLog
import com.trendpilot.app.data.RoomIntentStore
import com.trendpilot.app.data.RoomPositionStore
import com.trendpilot.app.data.RoomUsageLedger
import com.trendpilot.app.security.Secret
import com.trendpilot.app.security.SecureSettings
import com.trendpilot.app.service.Notifier
import com.trendpilot.app.settings.AppSettings
import com.trendpilot.app.settings.SettingsRepository
import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRouter
import com.trendpilot.core.ai.BudgetConfig
import com.trendpilot.core.ai.ProviderPricing
import com.trendpilot.core.engine.MarketDataSource
import com.trendpilot.core.engine.TradingEngine
import com.trendpilot.core.engine.UniverseSource
import com.trendpilot.core.execution.CancelReplaceResult
import com.trendpilot.core.execution.EngineEvent
import com.trendpilot.core.execution.EventSink
import com.trendpilot.core.execution.ExchangeGateway
import com.trendpilot.core.execution.ExchangeOrder
import com.trendpilot.core.execution.ExecutionConfig
import com.trendpilot.core.execution.ExecutionCoordinator
import com.trendpilot.core.execution.NewOrder
import com.trendpilot.core.execution.SystemClock
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.Candle
import com.trendpilot.core.model.Interval
import com.trendpilot.core.model.PriceTick
import com.trendpilot.core.model.TradingMode
import com.trendpilot.core.paper.PaperBroker
import com.trendpilot.core.universe.UniverseSelector
import com.trendpilot.infra.ai.ClaudeConfig
import com.trendpilot.infra.ai.ClaudeProvider
import com.trendpilot.infra.ai.OpenAiConfig
import com.trendpilot.infra.ai.OpenAiProvider
import com.trendpilot.infra.binance.BinanceCredentials
import com.trendpilot.infra.binance.BinanceEnvironment
import com.trendpilot.infra.binance.BinanceGateway
import com.trendpilot.infra.binance.BinanceMarketData
import com.trendpilot.infra.binance.BinanceMarketStream
import com.trendpilot.infra.binance.BinanceRestClient
import com.trendpilot.infra.universe.CoinGeckoClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.math.BigDecimal

/**
 * Builds and owns the single TradingEngine for the selected mode. PAPER uses real Binance
 * public prices with the simulated broker; REAL uses the signed gateway (testnet by default).
 */
class EngineHolder(
    private val http: OkHttpClient,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secure: SecureSettings,
    private val notifier: Notifier,
) {
    private val mutex = Mutex()
    private var engine: TradingEngine? = null
    private var builtKey: String? = null
    private var initialized = false
    var paperBroker: PaperBroker? = null
        private set
    var binanceGateway: BinanceGateway? = null
        private set
    private val rulesCache = mutableMapOf<String, SymbolRules>()

    /** Public market data. Paper and production use the production data host; testnet uses its own. */
    fun marketData(env: BinanceEnvironment = BinanceEnvironment.PRODUCTION): BinanceMarketData =
        BinanceMarketData(BinanceRestClient(http, env, { null }))

    fun marketStream(s: AppSettings) = BinanceMarketStream(http, environmentFor(s))

    private fun environmentFor(s: AppSettings) =
        if (s.tradingMode == TradingMode.REAL && s.binanceEnvironment == "PRODUCTION") BinanceEnvironment.PRODUCTION
        else if (s.tradingMode == TradingMode.REAL) BinanceEnvironment.TESTNET
        else BinanceEnvironment.PRODUCTION

    suspend fun engine(): TradingEngine = mutex.withLock {
        val s = settings.load()
        val key = "${s.mode}/${s.binanceEnvironment}"
        val existing = engine
        if (existing != null && key == builtKey) return@withLock existing
        val built = build(s)
        engine = built
        builtKey = key
        initialized = false
        built
    }

    /** Initialises once per built engine (loads persisted flags). */
    suspend fun ready(): TradingEngine {
        val e = engine()
        mutex.withLock {
            if (!initialized) {
                e.init()
                initialized = true
            }
        }
        return e
    }

    suspend fun reset() = mutex.withLock { engine = null; builtKey = null; initialized = false; paperBroker = null; binanceGateway = null }

    private suspend fun build(s: AppSettings): TradingEngine {
        val env = environmentFor(s)
        val market = marketData(env)
        val mode = s.tradingMode
        val rules: suspend (String) -> SymbolRules = { symbol ->
            rulesCache[symbol] ?: market.exchangeInfo(listOf(symbol)).getValue(symbol).also { rulesCache[symbol] = it }
        }

        val gateway: ExchangeGateway = when (mode) {
            TradingMode.PAPER -> {
                val broker = PaperBroker(BigDecimal.valueOf(s.paperInitialCapital), rules)
                db.kv().get(PAPER_KEY)?.let { raw -> runCatching { broker.importState(PaperStateCodec.decode(raw)) } }
                if (s.paperStartedAtMs == null) settings.update { it.copy(paperStartedAtMs = System.currentTimeMillis()) }
                paperBroker = broker
                PersistingGateway(broker) { db.kv().put(KvEntity(PAPER_KEY, PaperStateCodec.encode(broker.exportState()))) }
            }
            TradingMode.REAL -> {
                // Warm the decrypted cache so the signing callback never blocks on disk.
                secure.get(Secret.BINANCE_API_KEY); secure.get(Secret.BINANCE_SECRET)
                val rest = BinanceRestClient(http, env, {
                    val k = secure.getBlocking(Secret.BINANCE_API_KEY)
                    val sec = secure.getBlocking(Secret.BINANCE_SECRET)
                    if (k.isNullOrBlank() || sec.isNullOrBlank()) null else BinanceCredentials(k, sec)
                })
                runCatching { rest.syncTime() }
                BinanceGateway(rest, market, TradingMode.REAL).also { binanceGateway = it }
            }
            TradingMode.BACKTEST -> error("el backtest no usa el motor en vivo")
        }

        val dataSource = object : MarketDataSource {
            override suspend fun dailyCandles(symbol: String, count: Int): List<Candle> = market.klines(symbol, Interval.D1, count)
            override suspend fun rules(symbols: Collection<String>) = market.exchangeInfo(symbols).also { rulesCache.putAll(it) }
            override suspend fun tickers(symbols: Collection<String>) = market.ticker24h(symbols)
        }
        secure.get(Secret.ANTHROPIC_API_KEY); secure.get(Secret.OPENAI_API_KEY); secure.get(Secret.COINGECKO_DEMO_KEY)
        val coinGecko = CoinGeckoClient(http, demoApiKey = { secure.getBlocking(Secret.COINGECKO_DEMO_KEY) })
        val universe = UniverseSource { now ->
            val caps = coinGecko.topByMarketCap(100)
            val candidates = caps.map { it.symbol.uppercase() + "USDT" }.distinct()
            val r = runCatching { market.exchangeInfo(null) }.getOrElse { market.exchangeInfo(candidates.take(60)) }
            rulesCache.putAll(r.filterKeys { it in candidates })
            val tickers = market.ticker24h(candidates.filter { it in r })
            UniverseSelector.select(caps, r, tickers, now)
        }

        val sink = EventSink { e: EngineEvent ->
            db.events().insert(EventEntity(0, e.level.name, e.mode.name, e.title, e.detail, e.symbol, e.timestampMs))
            notifier.onEvent(e, settings.current)
        }

        val providers = buildList<Pair<AiProvider, BudgetConfig>> {
            val claude = ClaudeProvider({ secure.getBlocking(Secret.ANTHROPIC_API_KEY) }, {
                val c = settings.current
                ClaudeConfig(model = c.claudeModel, effort = c.claudeEffort, serverSideFallback = c.claudeServerFallback)
            }) to BudgetConfig(s.claudeBudgetUsd, ProviderPricing(s.claudeInputPerMTok, s.claudeOutputPerMTok), estimatedCallCost(s.claudeInputPerMTok, s.claudeOutputPerMTok))
            val openai = OpenAiProvider(http, { secure.getBlocking(Secret.OPENAI_API_KEY) }, { OpenAiConfig(model = settings.current.openAiModel) }) to
                BudgetConfig(s.openAiBudgetUsd, ProviderPricing(s.openAiInputPerMTok, s.openAiOutputPerMTok), estimatedCallCost(s.openAiInputPerMTok, s.openAiOutputPerMTok))
            if (s.primaryProvider == "openai") { add(openai); add(claude) } else { add(claude); add(openai) }
        }
        val router = AiRouter(providers, RoomUsageLedger(db.aiUsage()))
        val positions = RoomPositionStore(db.positions(), db.trades())
        val intents = RoomIntentStore(db.intents())

        return TradingEngine(
            gateway = gateway, market = dataSource, universeSource = universe, positions = positions,
            stateStore = KvEngineStateStore(db.kv()), events = sink, decisions = RoomDecisionLog(db.decisions()), ai = router,
            config = { settings.current.engineConfig() }, clock = SystemClock,
        ) { guards ->
            ExecutionCoordinator(gateway, intents, positions, sink, guards, rules, ExecutionConfig(feeRate = 0.001), SystemClock)
        }
    }

    /** Paper mode: feed real Binance prices into the simulated broker (triggers simulated stops). */
    suspend fun refreshPaperPrices(symbols: Collection<String>) {
        val broker = paperBroker ?: return
        val tickers = runCatching { marketData().ticker24h(symbols.toSet() + "BTCUSDT") }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        tickers.values.forEach { broker.updatePrice(PriceTick(it.symbol, it.lastPrice, now)) }
        persistPaper()
    }

    suspend fun onPaperTick(tick: PriceTick) {
        val broker = paperBroker ?: return
        val before = broker.openOrders().size
        broker.updatePrice(tick)
        if (broker.openOrders().size != before) persistPaper()
    }

    suspend fun persistPaper() {
        val broker = paperBroker ?: return
        db.kv().put(KvEntity(PAPER_KEY, PaperStateCodec.encode(broker.exportState())))
    }

    suspend fun recordEquity(mode: TradingMode, equity: BigDecimal?) {
        equity ?: return
        val btc = runCatching { marketData().price("BTCUSDT").price.toDouble() }.getOrNull() ?: return
        db.equity().insert(EquityEntity(0, mode.name, System.currentTimeMillis(), equity.toDouble(), btc))
    }

    /** Resets the simulated account (new 30-day paper period). */
    suspend fun resetPaper(capital: Double) {
        db.kv().put(KvEntity(PAPER_KEY, PaperStateCodec.encode(PaperBroker.State(mapOf("USDT" to BigDecimal.valueOf(capital)), emptyMap(), emptyList(), 1))))
        settings.update { it.copy(paperInitialCapital = capital, paperStartedAtMs = System.currentTimeMillis()) }
        reset()
    }

    private fun estimatedCallCost(inPerM: Double, outPerM: Double) = 20_000 / 1e6 * inPerM + 3_000 / 1e6 * outPerM

    companion object { const val PAPER_KEY = "paper_broker_state" }
}

/** Persists the simulated account after every mutating call. */
private class PersistingGateway(private val broker: PaperBroker, private val persist: suspend () -> Unit) : ExchangeGateway by broker {
    override suspend fun placeOrder(order: NewOrder): ExchangeOrder = try { broker.placeOrder(order) } finally { persist() }
    override suspend fun cancelOrder(symbol: String, clientOrderId: String): ExchangeOrder = try { broker.cancelOrder(symbol, clientOrderId) } finally { persist() }
    override suspend fun cancelReplace(symbol: String, cancelClientOrderId: String, newOrder: NewOrder): CancelReplaceResult =
        try { broker.cancelReplace(symbol, cancelClientOrderId, newOrder) } finally { persist() }
    override suspend fun amendQuantity(symbol: String, clientOrderId: String, newQuantity: BigDecimal): ExchangeOrder =
        try { broker.amendQuantity(symbol, clientOrderId, newQuantity) } finally { persist() }
}
