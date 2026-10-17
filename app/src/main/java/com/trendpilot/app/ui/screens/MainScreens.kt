package com.trendpilot.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trendpilot.app.data.EquityEntity
import com.trendpilot.app.ui.Amber
import com.trendpilot.app.ui.AppLogo
import com.trendpilot.app.ui.Authenticate
import com.trendpilot.app.ui.ConfirmDialog
import com.trendpilot.app.ui.Green
import com.trendpilot.app.ui.KeyValue
import com.trendpilot.app.ui.MainViewModel
import com.trendpilot.app.ui.Red
import com.trendpilot.app.ui.Section
import com.trendpilot.app.ui.fmtDuration
import com.trendpilot.app.ui.fmtMoney
import com.trendpilot.app.ui.fmtPct
import com.trendpilot.app.ui.fmtTime
import com.trendpilot.app.ui.pnlColor
import kotlin.math.max

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val st by vm.engineState.collectAsState()
    val settings by vm.settings.collectAsState()
    val positions by vm.positions.collectAsState()
    val prices by vm.prices.collectAsState()
    val trades by vm.trades.collectAsState()
    val views = vm.positionViews(positions, prices)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(44)
            Spacer(Modifier.padding(6.dp))
            Column {
                Text(if (settings.mode == "REAL") "Cuenta REAL (${settings.binanceEnvironment})" else "Simulación (paper): no es dinero real",
                    color = if (settings.mode == "REAL") Amber else Green, fontWeight = FontWeight.Bold)
                Text("No se garantiza rentabilidad. El stop no garantiza precio.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Capital") {
            val equity = st?.lastEquity?.toDouble()
            val exposure = st?.lastExposurePct
            KeyValue("Patrimonio del bot", "${fmtMoney(equity)} USDT")
            KeyValue("Exposición", exposure?.let { "%.1f%% (máx. %.0f%%)".format(it, settings.maxTotalExposurePct) } ?: "—")
            KeyValue("Reserva USDT", exposure?.let { "%.1f%% (mín. %.0f%%)".format(100 - it, settings.minReservePct) } ?: "—")
            val realized = trades.sumOf { it.pnlQuote.toDouble() }
            val unrealized = views.sumOf { it.unrealizedQuote ?: 0.0 }
            KeyValue("P&L realizado neto", fmtMoney(realized), pnlColor(realized))
            KeyValue("P&L no realizado", fmtMoney(unrealized), pnlColor(unrealized))
            KeyValue("Comisiones pagadas", fmtMoney(trades.sumOf { it.feesQuote.toDouble() }))
            st?.equityGuard?.let { g ->
                val dd = if (equity != null && g.peakEquity.signum() > 0) (g.peakEquity.toDouble() - equity) / g.peakEquity.toDouble() * 100 else 0.0
                KeyValue("Drawdown desde máximo", "%.2f%% (límite %.0f%%)".format(dd, settings.maxDrawdownPct), if (dd > settings.maxDrawdownPct * 0.75) Amber else Color.Unspecified)
            }
        }
        Section("Estado del bot") {
            val s = st
            KeyValue("Estrategia", if (s?.strategyRunning == true) "en ejecución" else "detenida", if (s?.strategyRunning == true) Green else Amber)
            KeyValue("Compras", when {
                s == null -> "—"
                s.emergency -> "BLOQUEADAS (emergencia)"
                s.equityGuard?.drawdownLocked == true -> "bloqueadas (drawdown)"
                s.equityGuard?.dailyLossLocked == true -> "bloqueadas (pérdida diaria)"
                s.defensiveActive -> "bloqueadas (modo defensivo)"
                s.entriesPausedByUser -> "pausadas por vos"
                s.connectivity.needsManualResume -> "esperan tu confirmación tras desconexión"
                !s.aiAvailable && settings.requireAiConfirmation -> "bloqueadas (IA no disponible)"
                else -> "habilitadas"
            })
            KeyValue("Conexión exchange", if (s?.connectivity?.connected == true) "OK" else "sin conexión", if (s?.connectivity?.connected == true) Green else Red)
            KeyValue("Reconciliación", if (s?.reconciliationClean == true) "limpia" else "pendiente/discrepancias", if (s?.reconciliationClean == true) Green else Amber)
            KeyValue("Modo defensivo", if (s?.defensiveActive == true) "ACTIVO" else "inactivo", if (s?.defensiveActive == true) Amber else Color.Unspecified)
            if (s?.defensiveActive == true) Text(s.defensiveTriggers.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            KeyValue("Última sincronización", fmtTime(s?.lastMonitorMs))
            KeyValue("Último análisis (cada 4h)", fmtTime(s?.lastAnalysisMs))
            KeyValue("Universo", "${s?.universe?.selected?.size ?: 0} activos")
            s?.aiLastError?.let { Text("IA: $it", color = Amber, style = MaterialTheme.typography.bodySmall) }
        }
        Section("Controles") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st?.strategyRunning == true) OutlinedButton(onClick = vm::stopStrategy) { Text("Detener análisis") }
                else Button(onClick = vm::startStrategy) { Text("Iniciar") }
                OutlinedButton(onClick = { vm.pauseEntries(st?.entriesPausedByUser != true) }) {
                    Text(if (st?.entriesPausedByUser == true) "Reanudar compras" else "Pausar compras")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::reconcileNow) { Text("Sincronizar") }
                OutlinedButton(onClick = vm::analyzeNow) { Text("Analizar ahora") }
            }
            Text("Detener o pausar nunca cancela las protecciones en el exchange.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::exportLog) { Text("Exportar log (.txt)") }
            Text("Genera un informe sin claves para compartir y diagnosticar.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PositionsScreen(vm: MainViewModel, authenticate: Authenticate) {
    val positions by vm.positions.collectAsState()
    val prices by vm.prices.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.padding(vertical = 8.dp)) {
        if (positions.isEmpty()) item { Section("Sin posiciones abiertas") { Text("El bot no está obligado a usar todo el cupo.") } }
        items(vm.positionViews(positions, prices), key = { it.p.id }) { v ->
            val p = v.p
            Section("${p.symbol} · ${p.setup}") {
                KeyValue("Estado", p.status, if (p.status == "PROTECTED") Green else Red)
                KeyValue("Cantidad", p.quantity.toPlainString())
                KeyValue("Entrada", p.entryPrice.round(java.math.MathContext(8)).toPlainString())
                KeyValue("Precio actual", v.lastPrice?.toPlainString() ?: "—")
                KeyValue("P&L", "${fmtMoney(v.unrealizedQuote)} (${fmtPct(v.unrealizedPct)})", pnlColor(v.unrealizedPct))
                KeyValue("Stop actual", "${p.currentStop.toPlainString()} (${p.stopType})")
                KeyValue("Stop inicial", p.initialStop.toPlainString())
                KeyValue("Riesgo al stop", fmtPct((p.currentStop.toDouble() / p.entryPrice.toDouble() - 1) * 100))
                KeyValue("Tiempo en posición", fmtDuration(now - p.openedAtMs))
                Text("Motivos: ${p.entryReasons}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { authenticate("Confirmar venta de ${p.symbol}") { confirm = p.id } }) { Text("Vender a mercado…", color = Red) }
            }
        }
    }
    confirm?.let { id ->
        ConfirmDialog("Vender posición", "Se cancelará SOLO el stop de esta posición e inmediatamente se venderá a mercado. Si la venta falla, la protección se repone.",
            onConfirm = { vm.closePosition(id) }, onDismiss = { confirm = null })
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    var tab by remember { mutableStateOf(0) }
    val decisions by vm.decisions.collectAsState()
    val events by vm.events.collectAsState()
    val trades by vm.trades.collectAsState()
    val intents by vm.intents.collectAsState()
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("Decisiones", "Alertas", "Operaciones", "Órdenes").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        LazyColumn(Modifier.padding(vertical = 8.dp)) {
            when (tab) {
                0 -> items(decisions, key = { it.id }) { d ->
                    Section("${fmtTime(d.timestampMs)} · ${d.kind}${d.symbol?.let { " · $it" } ?: ""}") {
                        Text(d.summary, fontWeight = FontWeight.Medium)
                        if (d.details.isNotBlank()) Text(d.details, style = MaterialTheme.typography.bodySmall)
                        d.aiProvider?.let { Text("IA: $it", style = MaterialTheme.typography.labelSmall, color = Amber) }
                    }
                }
                1 -> items(events, key = { it.id }) { e ->
                    Section("${fmtTime(e.timestampMs)} · ${e.level}") {
                        Text(e.title, fontWeight = FontWeight.Medium, color = if (e.level == "EMERGENCY" || e.level == "SECURITY") Red else Color.Unspecified)
                        Text(e.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                2 -> items(trades, key = { it.id }) { t ->
                    Section("${t.symbol} · ${fmtTime(t.closedAtMs)}") {
                        KeyValue("Resultado neto", "${fmtMoney(t.pnlQuote.toDouble())} (${"%.2f".format(t.rMultiple)}R)", pnlColor(t.pnlQuote.toDouble()))
                        KeyValue("Entrada → salida", "${t.entryPrice.round(java.math.MathContext(6))} → ${t.exitPrice.round(java.math.MathContext(6))}")
                        KeyValue("Comisiones", fmtMoney(t.feesQuote.toDouble()))
                        KeyValue("Motivo", t.exitReason)
                        KeyValue("Duración", fmtDuration(t.closedAtMs - t.openedAtMs))
                    }
                }
                else -> items(intents, key = { it.id }) { o ->
                    Section("${fmtTime(o.createdAtMs)} · ${o.kind} ${o.symbol}") {
                        KeyValue("Tipo", "${o.side} ${o.type}")
                        KeyValue("Cantidad", o.quantity.toPlainString())
                        o.stopPrice?.let { KeyValue("Stop", it.toPlainString()) }
                        KeyValue("Estado", o.status)
                        KeyValue("clientOrderId", o.clientOrderId)
                        if (o.note.isNotBlank()) Text(o.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun StatsScreen(vm: MainViewModel) {
    val equity by vm.equity.collectAsState()
    val trades by vm.trades.collectAsState()
    val usage by vm.aiUsage.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Curva de patrimonio vs BTC buy-and-hold") {
            if (equity.size < 2) Text("Se registrará a medida que el motor supervise la cuenta.")
            else {
                EquityChart(equity)
                val first = equity.first()
                val last = equity.last()
                val btcQty = first.equity / first.btcPrice
                KeyValue("Bot", fmtPct((last.equity / first.equity - 1) * 100), pnlColor(last.equity - first.equity))
                KeyValue("BTC mismo capital y período", fmtPct((btcQty * last.btcPrice / first.equity - 1) * 100))
                var peak = 0.0
                var maxDd = 0.0
                equity.forEach { peak = max(peak, it.equity); maxDd = max(maxDd, (peak - it.equity) / peak * 100) }
                KeyValue("Drawdown máximo", "%.2f%%".format(maxDd))
                Text("Verde: bot · Ámbar: BTC buy-and-hold", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Operaciones") {
            val wins = trades.count { it.pnlQuote.signum() > 0 }
            val grossWin = trades.filter { it.pnlQuote.signum() > 0 }.sumOf { it.pnlQuote.toDouble() }
            val grossLoss = -trades.filter { it.pnlQuote.signum() <= 0 }.sumOf { it.pnlQuote.toDouble() }
            KeyValue("Cerradas", "${trades.size}")
            KeyValue("Win rate", if (trades.isEmpty()) "—" else "%.1f%%".format(wins * 100.0 / trades.size))
            KeyValue("Profit factor", if (grossLoss == 0.0) "—" else "%.2f".format(grossWin / grossLoss))
            KeyValue("R promedio", if (trades.isEmpty()) "—" else "%.2f".format(trades.sumOf { it.rMultiple } / trades.size))
            KeyValue("P&L neto", fmtMoney(trades.sumOf { it.pnlQuote.toDouble() }), pnlColor(trades.sumOf { it.pnlQuote.toDouble() }))
            KeyValue("Comisiones", fmtMoney(trades.sumOf { it.feesQuote.toDouble() }))
            if (trades.size < 30) Text("Muestra pequeña: no extraigas conclusiones estadísticas.", color = Amber, style = MaterialTheme.typography.bodySmall)
        }
        Section("Rentabilidad por estrategia") {
            trades.groupBy { it.setup }.forEach { (setup, list) ->
                KeyValue(setup, "${list.size} ops · ${fmtMoney(list.sumOf { it.pnlQuote.toDouble() })}", pnlColor(list.sumOf { it.pnlQuote.toDouble() }))
            }
            if (trades.isEmpty()) Text("Sin datos todavía.")
        }
        Section("Costo de IA") {
            val month = usage.filter { it.timestampMs > System.currentTimeMillis() - 30L * 86_400_000 }
            month.groupBy { it.provider }.forEach { (p, l) ->
                KeyValue(p, "US$ %.2f · %d llamadas · %d fallos".format(l.sumOf { it.costUsd }, l.size, l.count { !it.ok }))
            }
            if (month.isEmpty()) Text("Sin llamadas en los últimos 30 días.")
        }
    }
}

@Composable
fun EquityChart(points: List<EquityEntity>) {
    val first = points.first()
    val btcQty = first.equity / first.btcPrice
    val bot = points.map { it.equity }
    val btc = points.map { btcQty * it.btcPrice }
    val lo = (bot + btc).min()
    val hi = (bot + btc).max().let { if (it == lo) lo + 1 else it }
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        fun path(series: List<Double>): Path {
            val p = Path()
            series.forEachIndexed { i, v ->
                val x = size.width * i / (series.size - 1).coerceAtLeast(1)
                val y = size.height * (1 - ((v - lo) / (hi - lo)).toFloat())
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(path(btc), Amber, style = Stroke(width = 3f))
        drawPath(path(bot), Green, style = Stroke(width = 4f))
    }
}

@Composable
fun PaperScreen(vm: MainViewModel, authenticate: Authenticate) {
    val settings by vm.settings.collectAsState()
    val balance by vm.paperBalance.collectAsState()
    val trades by vm.trades.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Cuenta simulada") {
            if (settings.mode != "PAPER") Text("Estás en modo ${settings.mode}. El paper trading usa su propio libro, separado.", color = Amber)
            KeyValue("Capital inicial", "${fmtMoney(settings.paperInitialCapital)} USDT")
            KeyValue("Patrimonio virtual", "${fmtMoney(balance?.toDouble())} USDT")
            KeyValue("Operaciones cerradas", "${trades.size}")
            Text("Precios reales de Binance, órdenes simuladas con comisiones y slippage conservadores.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Período mínimo de 30 días") {
            val started = settings.paperStartedAtMs
            val days = started?.let { (System.currentTimeMillis() - it) / 86_400_000L } ?: 0
            KeyValue("Inicio", fmtTime(started))
            KeyValue("Transcurrido", "$days / 30 días", if (days >= 30) Green else Amber)
            if (days < 30) KeyValue("Restan", "${30 - days} días")
            Text("Completar 30 días NO habilita el modo real automáticamente: requiere auditoría de resultados, incidentes y tu consentimiento separado en Seguridad.",
                style = MaterialTheme.typography.bodySmall)
        }
        Section("Reiniciar") {
            OutlinedButton(onClick = { authenticate("Reiniciar cuenta paper") { confirm = true } }) { Text("Reiniciar cuenta paper…", color = Red) }
        }
    }
    if (confirm) ConfirmDialog("Reiniciar paper", "Se borra el saldo virtual y reinicia el conteo de 30 días. El historial se conserva.", typed = "REINICIAR",
        onConfirm = { vm.resetPaper(settings.paperInitialCapital) }, onDismiss = { confirm = false })
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text(text, color = Color.White) }
}
