package com.trendpilot.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trendpilot.app.security.Secret
import com.trendpilot.app.settings.AppSettings
import com.trendpilot.app.ui.Amber
import com.trendpilot.app.ui.Authenticate
import com.trendpilot.app.ui.BacktestUi
import com.trendpilot.app.ui.ConfirmDialog
import com.trendpilot.app.ui.Green
import com.trendpilot.app.ui.KeyValue
import com.trendpilot.app.ui.MainViewModel
import com.trendpilot.app.ui.Red
import com.trendpilot.app.ui.Section
import com.trendpilot.app.ui.fmtMoney
import com.trendpilot.app.ui.fmtPct
import com.trendpilot.app.ui.pnlColor

@Composable
fun BacktestScreen(vm: MainViewModel) {
    val ui by vm.backtest.collectAsState()
    var days by remember { mutableStateOf("730") }
    var capital by remember { mutableStateOf("200") }
    var folds by remember { mutableStateOf("4") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Parámetros") {
            NumberField("Días de historia (diario)", days) { days = it }
            NumberField("Capital inicial (USDT)", capital) { capital = it }
            NumberField("Ventanas walk-forward", folds) { folds = it }
            Button(
                onClick = { vm.runBacktest(days.toIntOrNull()?.coerceIn(300, 3000) ?: 730, capital.toDoubleOrNull() ?: 200.0, folds.toIntOrNull()?.coerceIn(2, 8) ?: 4) },
                enabled = ui !is BacktestUi.Running,
            ) { Text("Ejecutar backtest") }
            Text("Velas diarias cerradas, sin look-ahead: decisión al cierre, ejecución en la apertura siguiente. Separado por completo de paper y real.",
                style = MaterialTheme.typography.bodySmall)
        }
        when (val s = ui) {
            BacktestUi.Idle -> Unit
            is BacktestUi.Running -> Section("Ejecutando") { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.progress) }
            is BacktestUi.Error -> Section("Error") { Text(s.message, color = Red) }
            is BacktestUi.Done -> {
                val r = s.result
                Section("Resultado (período completo)") {
                    KeyValue("Retorno estrategia", fmtPct(r.totalReturnPct), pnlColor(r.totalReturnPct))
                    KeyValue("BTC buy-and-hold", fmtPct(r.btcReturnPct), pnlColor(r.btcReturnPct))
                    KeyValue("Drawdown máximo", "%.2f%%".format(r.maxDrawdownPct))
                    KeyValue("Operaciones", "${r.trades.size}")
                    KeyValue("Win rate", "%.1f%%".format(r.winRatePct))
                    KeyValue("Profit factor", if (r.profitFactor.isInfinite()) "∞" else "%.2f".format(r.profitFactor))
                    KeyValue("R promedio", "%.2f".format(r.avgR))
                    KeyValue("Comisiones", fmtMoney(r.totalFees))
                    KeyValue("Exposición media", "%.1f%%".format(r.avgExposurePct))
                    Text("Activos: ${s.symbols.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                Section("Advertencias de sesgo") { r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Amber) } }
                Section("Walk-forward (fuera de muestra, parámetros fijos)") {
                    s.folds.forEachIndexed { i, f ->
                        KeyValue("Ventana ${i + 1}", "${fmtPct(f.totalReturnPct)} vs BTC ${fmtPct(f.btcReturnPct)} · DD %.1f%% · %d ops".format(f.maxDrawdownPct, f.trades.size), pnlColor(f.totalReturnPct))
                    }
                    Text("Resultados inestables entre ventanas indican dependencia de un régimen concreto.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
fun SettingsScreen(vm: MainViewModel, authenticate: Authenticate) {
    val s by vm.settings.collectAsState()
    val masked by vm.secretsMasked.collectAsState()
    var unlocked by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Secret?>(null) }
    var draft by remember(s) { mutableStateOf(s) }
    var blockInput by remember { mutableStateOf("") }
    val state by vm.engineState.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Credenciales (cifradas con Android Keystore)") {
            if (!unlocked) {
                Text("Protegido con biometría/PIN.")
                Button(onClick = { authenticate("Ver y editar credenciales") { unlocked = true; vm.refreshSecrets() } }) { Text("Desbloquear") }
            } else {
                Secret.entries.forEach { sec ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sec.label, fontWeight = FontWeight.Medium)
                            Text(masked[sec] ?: "…", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { editing = sec }) { Text("Cambiar") }
                    }
                }
                Text("Binance: creá la key SIN permiso de retiros y solo con Spot. Las claves nunca se envían a la IA, ni a logs ni informes.",
                    style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }
        Section("Proveedor de IA") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(draft.primaryProvider == "gemini", { draft = draft.copy(primaryProvider = "gemini") }, label = { Text("Gemini") })
                FilterChip(draft.primaryProvider == "claude", { draft = draft.copy(primaryProvider = "claude") }, label = { Text("Claude") })
                FilterChip(draft.primaryProvider == "openai", { draft = draft.copy(primaryProvider = "openai") }, label = { Text("GPT") })
            }
            Text("El elegido va primero; los demás quedan de respaldo (Gemini principal → Claude → GPT).", style = MaterialTheme.typography.bodySmall)
            TextSetting("Modelo Gemini", draft.geminiModel) { draft = draft.copy(geminiModel = it) }
            NumSetting("Presupuesto mensual Gemini (USD)", draft.geminiBudgetUsd) { draft = draft.copy(geminiBudgetUsd = it) }
            NumSetting("Gemini US$/M tokens entrada (0 = gratis)", draft.geminiInputPerMTok) { draft = draft.copy(geminiInputPerMTok = it) }
            NumSetting("Gemini US$/M tokens salida (0 = gratis)", draft.geminiOutputPerMTok) { draft = draft.copy(geminiOutputPerMTok = it) }
            TextSetting("Modelo Claude", draft.claudeModel) { draft = draft.copy(claudeModel = it) }
            TextSetting("Esfuerzo Claude (low/medium/high)", draft.claudeEffort) { draft = draft.copy(claudeEffort = it) }
            NumSetting("Presupuesto mensual Claude (USD)", draft.claudeBudgetUsd) { draft = draft.copy(claudeBudgetUsd = it) }
            NumSetting("Claude US$/M tokens entrada", draft.claudeInputPerMTok) { draft = draft.copy(claudeInputPerMTok = it) }
            NumSetting("Claude US$/M tokens salida", draft.claudeOutputPerMTok) { draft = draft.copy(claudeOutputPerMTok = it) }
            TextSetting("Modelo OpenAI", draft.openAiModel) { draft = draft.copy(openAiModel = it) }
            NumSetting("Presupuesto mensual OpenAI (USD)", draft.openAiBudgetUsd) { draft = draft.copy(openAiBudgetUsd = it) }
            NumSetting("OpenAI US$/M tokens entrada (verificar)", draft.openAiInputPerMTok) { draft = draft.copy(openAiInputPerMTok = it) }
            NumSetting("OpenAI US$/M tokens salida (verificar)", draft.openAiOutputPerMTok) { draft = draft.copy(openAiOutputPerMTok = it) }
            SwitchSetting("Exigir confirmación de IA para comprar", draft.requireAiConfirmation) { draft = draft.copy(requireAiConfirmation = it) }
            Text("Si todos los proveedores fallan o agotan presupuesto, no se abren posiciones nuevas.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Límites de riesgo") {
            NumSetting("Riesgo por operación (% patrimonio)", draft.maxRiskPerTradePct) { draft = draft.copy(maxRiskPerTradePct = it) }
            NumSetting("Exposición máxima total (%)", draft.maxTotalExposurePct) { draft = draft.copy(maxTotalExposurePct = it) }
            NumSetting("Reserva mínima USDT (%)", draft.minReservePct) { draft = draft.copy(minReservePct = it) }
            NumSetting("Máximo por activo (%)", draft.maxPerAssetPct) { draft = draft.copy(maxPerAssetPct = it) }
            NumSetting("Posiciones simultáneas", draft.maxPositions.toDouble()) { draft = draft.copy(maxPositions = it.toInt()) }
            NumSetting("Drawdown máximo (%)", draft.maxDrawdownPct) { draft = draft.copy(maxDrawdownPct = it) }
            NumSetting("Pérdida diaria máxima (%)", draft.dailyLossLimitPct) { draft = draft.copy(dailyLossLimitPct = it) }
            NumSetting("Slippage conservador por lado (%)", draft.slippagePct) { draft = draft.copy(slippagePct = it) }
            NumSetting("Modo defensivo: caída BTC 24h (%) [parámetro de prueba]", draft.btcDefensiveDrop24hPct) { draft = draft.copy(btcDefensiveDrop24hPct = it) }
        }
        Section("Notificaciones") {
            SwitchSetting("Compras y ventas", draft.notifyTrades) { draft = draft.copy(notifyTrades = it) }
            SwitchSetting("Cambios de stop", draft.notifyStops) { draft = draft.copy(notifyStops = it) }
            SwitchSetting("Conectividad y recuperación", draft.notifyConnectivity) { draft = draft.copy(notifyConnectivity = it) }
            SwitchSetting("Permitir capturas de pantalla", draft.allowScreenshots) { draft = draft.copy(allowScreenshots = it) }
            Text("Las API keys siempre se muestran enmascaradas. Desactivá las capturas si querés ocultar saldos en recientes.", style = MaterialTheme.typography.bodySmall)
            Text("Las alertas de seguridad y emergencia no se pueden silenciar.", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { authenticate("Guardar ajustes") { vm.updateSettings {
                        draft.copy(
                            mode = it.mode, binanceEnvironment = it.binanceEnvironment, paperStartedAtMs = it.paperStartedAtMs,
                            paperInitialCapital = it.paperInitialCapital, ackPaperReviewed = it.ackPaperReviewed,
                            ackStopNoGuarantee = it.ackStopNoGuarantee, ackSmallSize = it.ackSmallSize, ackNoWithdrawKey = it.ackNoWithdrawKey,
                        )
                    } } }) { Text("Guardar ajustes") }
            OutlinedButton(onClick = { draft = s }) { Text("Descartar") }
        }
        Section("Monedas bloqueadas") {
            Text(state?.blockedSymbols?.joinToString().orEmpty().ifEmpty { "Ninguna" })
            OutlinedTextField(blockInput, { blockInput = it }, label = { Text("Símbolo (ej. DOGE)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.blockSymbol(blockInput, true); blockInput = "" }) { Text("Bloquear") }
                OutlinedButton(onClick = { vm.blockSymbol(blockInput, false); blockInput = "" }) { Text("Desbloquear") }
            }
        }
    }
    editing?.let { sec ->
        var value by remember(sec) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(sec.label) },
            text = {
                OutlinedTextField(value, { value = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Pegá el valor (vacío = borrar)") })
            },
            confirmButton = { TextButton(onClick = { vm.saveSecret(sec, value); editing = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TextSetting(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NumSetting(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) }
    OutlinedTextField(
        text, { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchSetting(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
fun SecurityScreen(vm: MainViewModel, authenticate: Authenticate) {
    val st by vm.engineState.collectAsState()
    val s by vm.settings.collectAsState()
    val positions by vm.positions.collectAsState()
    var liquidate by remember { mutableStateOf(false) }
    var switchMode by remember { mutableStateOf<String?>(null) }
    var arm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (st?.emergency == true) Section("EMERGENCIA ACTIVA") {
            Text(st?.emergencyReason ?: "", color = Red, fontWeight = FontWeight.Bold)
            Text("Compras bloqueadas. Revisá Posiciones e Historial. Solo podés despejarla cuando todas las posiciones estén protegidas.")
            OutlinedButton(onClick = { authenticate("Despejar emergencia") { vm.clearEmergency() } }) { Text("Despejar emergencia") }
        }
        Section("Protecciones") {
            if (positions.isEmpty()) Text("Sin posiciones abiertas.")
            positions.forEach { p ->
                KeyValue(p.symbol, if (p.status == "PROTECTED") "stop ${p.currentStop} confirmado" else p.status, if (p.status == "PROTECTED") Green else Red)
            }
            KeyValue("Reconciliación", if (st?.reconciliationClean == true) "limpia" else "pendiente / discrepancias", if (st?.reconciliationClean == true) Green else Amber)
            OutlinedButton(onClick = vm::reconcileNow) { Text("Reconciliar ahora") }
        }
        Section("Bloqueos") {
            if (st?.connectivity?.needsManualResume == true) {
                Text("Hubo una desconexión larga o discrepancias: confirmá que revisaste el estado antes de reanudar compras.", color = Amber)
                Button(onClick = { authenticate("Reanudar tras desconexión") { vm.confirmResume() } }) { Text("Confirmar reanudación") }
            }
            if (st?.equityGuard?.drawdownLocked == true) {
                Text("Drawdown máximo alcanzado. La reactivación reinicia el máximo de referencia al patrimonio actual.", color = Amber)
                Button(onClick = { authenticate("Reactivar tras drawdown") { vm.reactivateDrawdown() } }) { Text("Reactivar compras") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.pauseEntries(true) }) { Text("Pausar compras") }
                OutlinedButton(onClick = vm::stopStrategy) { Text("Detener estrategia") }
            }
            Text("Pausar y detener no tocan las órdenes de protección.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Liquidación") {
            Text("Vende todas las posiciones a mercado. Cada stop se cancela solo inmediatamente antes de vender su posición.")
            DangerButton("Liquidar todo…") { authenticate("Liquidar todas las posiciones") { liquidate = true } }
        }
        Section("Modo de operación") {
            KeyValue("Modo actual", s.mode)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.mode == "PAPER", { switchMode = "PAPER" }, label = { Text("PAPER") })
                FilterChip(s.mode == "REAL" && s.binanceEnvironment == "TESTNET", { switchMode = "REAL/TESTNET" }, label = { Text("REAL testnet") })
                FilterChip(s.mode == "REAL" && s.binanceEnvironment == "PRODUCTION", { switchMode = "REAL/PRODUCTION" }, label = { Text("REAL producción") })
            }
            Text("Cada modo tiene su propio libro de posiciones, operaciones y auditoría.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Checklist para habilitar dinero real") {
            val days = s.paperStartedAtMs?.let { (System.currentTimeMillis() - it) / 86_400_000L } ?: 0
            KeyValue("Paper trading ≥ 30 días", "$days/30", if (days >= 30) Green else Red)
            Check("Revisé resultados, fiabilidad e incidentes del paper trading", s.ackPaperReviewed) { v -> vm.updateSettings { it.copy(ackPaperReviewed = v) } }
            Check("Entiendo que el stop no garantiza precio ni pérdida máxima", s.ackStopNoGuarantee) { v -> vm.updateSettings { it.copy(ackStopNoGuarantee = v) } }
            Check("Empezaré con un tamaño pequeño", s.ackSmallSize) { v -> vm.updateSettings { it.copy(ackSmallSize = v) } }
            KeyValue("API key verificada sin retiros", if (s.ackNoWithdrawKey) "sí" else "no", if (s.ackNoWithdrawKey) Green else Red)
            OutlinedButton(onClick = vm::verifyBinanceKey) { Text("Verificar permisos de la API key") }
            KeyValue("Modo REAL habilitado", if (st?.realModeArmed == true) "SÍ" else "no", if (st?.realModeArmed == true) Red else Green)
            if (st?.realModeArmed == true) OutlinedButton(onClick = { vm.armReal(false) }) { Text("Deshabilitar modo real") }
            else DangerButton("Habilitar operaciones reales…") { authenticate("Habilitar dinero real") { arm = true } }
        }
        Section("Batería y fabricante (POCO/HyperOS)") {
            Text("Android puede pausar la app con pantalla apagada. Para mejorar la supervisión: permití ejecución en segundo plano / 'Sin restricciones' de batería y el inicio automático en los ajustes del sistema. Aun así no se garantiza 24/7; los stops viven en Binance.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Abrir ajustes de la app") }
        }
    }
    if (liquidate) ConfirmDialog("Liquidar todo", "Se venderán TODAS las posiciones a mercado (${positions.size}).", typed = "LIQUIDAR",
        onConfirm = { vm.liquidateAll() }, onDismiss = { liquidate = false })
    switchMode?.let { target ->
        ConfirmDialog("Cambiar a $target", if (target.startsWith("REAL")) "El modo real queda deshabilitado hasta completar el checklist y confirmarlo por separado." else "Se usará la cuenta simulada.",
            typed = if (target == "REAL/PRODUCTION") "PRODUCCION" else null,
            onConfirm = {
                val (mode, env) = if (target == "PAPER") "PAPER" to s.binanceEnvironment else target.split("/").let { it[0] to it[1] }
                vm.updateSettings("Modo cambiado a $target") { it.copy(mode = mode, binanceEnvironment = env) }
            },
            onDismiss = { switchMode = null })
    }
    if (arm) ConfirmDialog("Habilitar dinero real", "Primera confirmación: se podrán enviar órdenes reales con tu dinero en ${s.binanceEnvironment}.", typed = "OPERAR REAL",
        onConfirm = { vm.armReal(true) }, onDismiss = { arm = false })
}

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
