package com.trendpilot.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trendpilot.app.data.AppDatabase
import com.trendpilot.app.data.DecisionEntity
import com.trendpilot.app.data.EquityEntity
import com.trendpilot.app.data.EventEntity
import com.trendpilot.app.data.IntentEntity
import com.trendpilot.app.data.PositionEntity
import com.trendpilot.app.data.TradeEntity
import com.trendpilot.app.engine.EngineHolder
import com.trendpilot.app.security.Secret
import com.trendpilot.app.security.SecureSettings
import com.trendpilot.app.service.EngineService
import com.trendpilot.app.settings.AppSettings
import com.trendpilot.app.settings.SettingsRepository
import com.trendpilot.core.engine.EngineState
import com.trendpilot.core.model.Candle
import com.trendpilot.core.model.Interval
import com.trendpilot.core.model.TradingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject

data class PositionView(val p: PositionEntity, val lastPrice: BigDecimal?) {
    val unrealizedPct: Double? get() = lastPrice?.let { (it.toDouble() / p.entryPrice.toDouble() - 1) * 100 }
    val unrealizedQuote: Double? get() = lastPrice?.let { it.toDouble() * p.quantity.toDouble() - p.costBasisQuote.toDouble() }
}

sealed interface ChartUi {
    data object Loading : ChartUi
    data class Ready(val candles: List<Candle>) : ChartUi
    data class Error(val message: String) : ChartUi
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    app: Application,
    private val holder: EngineHolder,
    private val db: AppDatabase,
    val settingsRepo: SettingsRepository,
    private val secure: SecureSettings,
    private val exporter: com.trendpilot.app.data.LogExporter,
) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val modeFlow = settings.map { it.mode }

    val positions = modeFlow.flatMapLatest { db.positions().openFlow(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val trades = modeFlow.flatMapLatest { db.trades().all(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<TradeEntity>())
    val decisions = modeFlow.flatMapLatest { db.decisions().recent(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<DecisionEntity>())
    val events = modeFlow.flatMapLatest { db.events().recent(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<EventEntity>())
    val intents = modeFlow.flatMapLatest { db.intents().recent(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<IntentEntity>())
    val equity = modeFlow.flatMapLatest { db.equity().all(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<EquityEntity>())
    val aiUsage = db.aiUsage().recent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _engineState = MutableStateFlow<EngineState?>(null)
    val engineState: StateFlow<EngineState?> = _engineState.asStateFlow()
    private val _prices = MutableStateFlow<Map<String, BigDecimal>>(emptyMap())
    val prices: StateFlow<Map<String, BigDecimal>> = _prices.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _secretsMasked = MutableStateFlow<Map<Secret, String>>(emptyMap())
    val secretsMasked: StateFlow<Map<Secret, String>> = _secretsMasked.asStateFlow()
    /** Chart open for a position (position id → state); null when closed. */
    private val _chart = MutableStateFlow<Pair<String, ChartUi>?>(null)
    val chart: StateFlow<Pair<String, ChartUi>?> = _chart.asStateFlow()
    private val _paperBalance = MutableStateFlow<BigDecimal?>(null)
    val paperBalance: StateFlow<BigDecimal?> = _paperBalance.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000)
            }
        }
        refreshSecrets()
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching {
            val engine = holder.ready()
            _engineState.value = engine.currentState()
            val symbols = engine.openPositions().map { it.symbol }
            val gw = holder.paperBroker ?: holder.binanceGateway
            if (gw != null) _prices.value = symbols.mapNotNull { s -> runCatching { s to gw.lastPrice(s).price }.getOrNull() }.toMap()
            _paperBalance.value = holder.paperBroker?.equity()
        }
    }

    fun positionViews(list: List<PositionEntity>, prices: Map<String, BigDecimal>) = list.map { PositionView(it, prices[it.symbol]) }

    fun toast(msg: String?) { _message.value = msg }

    /** Builds the diagnostic .txt (no secrets) and opens the Android share sheet. */
    fun exportLog() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { exporter.export(engineState.value) }
            .onSuccess { getApplication<Application>().startActivity(it) }
            .onFailure { _message.value = "No se pudo exportar el log: ${it.message}" }
    }

    private fun act(label: String, block: suspend () -> Any?) = viewModelScope.launch(Dispatchers.IO) {
        val r = runCatching { block() }
        _message.value = r.fold({ if (it is String) "$label: $it" else "$label: listo" }, { "$label: ${it.message}" })
        refresh()
    }

    // ---- controls (none of these cancel protections)
    fun startStrategy() = act("Estrategia iniciada") { holder.ready().startStrategy(); EngineService.start(getApplication()) }
    fun stopStrategy() = act("Estrategia detenida (protecciones intactas)") { holder.ready().stopStrategy() }
    fun pauseEntries(p: Boolean) = act(if (p) "Compras pausadas" else "Compras reanudadas") { holder.ready().pauseEntries(p) }
    fun reconcileNow() = act("Reconciliación") { holder.ready().monitorCycle() }
    fun analyzeNow() = act("Análisis") { holder.ready().analysisCycle(force = true) }
    fun confirmResume() = act("Reanudación confirmada") { holder.ready().confirmResumeAfterOutage() }
    fun reactivateDrawdown() = act("Reactivación tras drawdown") { holder.ready().reactivateAfterDrawdown() }
    fun clearEmergency() = act("Emergencia") { if (!holder.ready().clearEmergency()) error("hay posiciones sin protección confirmada") }
    fun closePosition(id: String) = act("Cierre de posición") { holder.ready().closePosition(id) }
    fun liquidateAll() = act("Liquidación total") { holder.ready().liquidateAll() }
    fun blockSymbol(symbol: String, blocked: Boolean) = act(if (blocked) "Bloqueado $symbol" else "Desbloqueado $symbol") {
        holder.ready().blockSymbol(symbol.uppercase().let { if (it.endsWith("USDT")) it else it + "USDT" }, blocked)
    }

    fun updateSettings(label: String = "Ajustes guardados", f: (AppSettings) -> AppSettings) = viewModelScope.launch(Dispatchers.IO) {
        val before = settingsRepo.load()
        val r = settingsRepo.update(f)
        _message.value = r.fold({ label }, { "No guardado: ${it.message}" })
        val after = r.getOrNull() ?: return@launch
        if (after.mode != before.mode || after.binanceEnvironment != before.binanceEnvironment ||
            after.primaryProvider != before.primaryProvider || after.geminiBudgetUsd != before.geminiBudgetUsd || after.claudeBudgetUsd != before.claudeBudgetUsd || after.openAiBudgetUsd != before.openAiBudgetUsd
        ) {
            holder.reset()
            EngineService.start(getApplication(), restart = true)
        }
    }

    fun saveSecret(secret: Secret, value: String) = viewModelScope.launch(Dispatchers.IO) {
        // Providers read keys lazily from SecureSettings, so the running engine picks the new key up
        // on its next call: no engine rebuild (which used to leave the service on a stale instance).
        secure.set(secret, value)
        refreshSecrets()
        _message.value = "${secret.label} guardada (cifrada)"
    }

    fun refreshSecrets() = viewModelScope.launch(Dispatchers.IO) {
        _secretsMasked.value = Secret.entries.associateWith { secure.masked(it) }
    }

    /** Checks the Binance key restrictions; refuses keys that allow withdrawals. */
    fun verifyBinanceKey() = act("Verificación de API key") {
        val gw = holder.binanceGateway ?: error("seleccioná modo REAL (testnet o producción) para verificar")
        val p = gw.apiKeyPermissions()
        if (p.enableWithdrawals) error("la key PERMITE RETIROS: revocala y creá una sin retiros")
        updateSettings("Key verificada sin retiros") { it.copy(ackNoWithdrawKey = true) }
        "retiros deshabilitados; spot ${p.enableSpotAndMarginTrading}; restricción IP ${p.ipRestrict}"
    }

    fun armReal(armed: Boolean) = act(if (armed) "Modo REAL habilitado" else "Modo REAL deshabilitado") {
        val s = settingsRepo.load()
        if (armed) {
            val days = s.paperStartedAtMs?.let { (System.currentTimeMillis() - it) / 86_400_000L } ?: 0
            require(days >= 30) { "paper trading: $days/30 días" }
            require(s.ackPaperReviewed && s.ackStopNoGuarantee && s.ackSmallSize && s.ackNoWithdrawKey) { "checklist incompleto" }
            require(s.tradingMode == TradingMode.REAL) { "primero seleccioná modo REAL" }
        }
        holder.ready().armRealMode(armed)
    }

    fun resetPaper(capital: Double) = act("Cuenta paper reiniciada") {
        holder.resetPaper(capital)
        EngineService.start(getApplication(), restart = true)
    }

    /** Loads 4h candles covering at least the last week (and the whole position) for the chart. */
    fun openChart(p: PositionEntity) = viewModelScope.launch(Dispatchers.IO) {
        _chart.value = p.id to ChartUi.Loading
        val now = System.currentTimeMillis()
        val since = minOf(now - 7 * 86_400_000L, p.openedAtMs - 86_400_000L)
        val count = ((now - since) / Interval.H4.millis + 1).toInt().coerceIn(42, 180)
        _chart.value = p.id to runCatching { holder.marketData().klines(p.symbol, Interval.H4, count) }.fold(
            { if (it.isEmpty()) ChartUi.Error("Binance no devolvió velas") else ChartUi.Ready(it) },
            { ChartUi.Error(it.message ?: it.javaClass.simpleName) },
        )
    }

    fun closeChart() { _chart.value = null }
}
