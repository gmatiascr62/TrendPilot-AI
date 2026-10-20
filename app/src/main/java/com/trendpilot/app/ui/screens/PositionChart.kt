package com.trendpilot.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trendpilot.app.data.PositionEntity
import com.trendpilot.app.ui.ChartUi
import com.trendpilot.app.ui.Green
import com.trendpilot.app.ui.Red
import com.trendpilot.app.ui.fmtPct
import com.trendpilot.core.model.Candle
import com.trendpilot.core.model.Interval
import com.trendpilot.core.strategy.StrategyConfig
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val EntryBlue = Color(0xFF64B5F6)
private val PriceWhite = Color(0xFFE6EDF3)

/** Price levels drawn over the candles. [target] is where the stop moves to breakeven (entry + 1.5R). */
private data class ChartLevels(val price: Double, val entry: Double, val stop: Double, val target: Double) {
    companion object {
        fun of(p: PositionEntity, price: Double): ChartLevels {
            val entry = p.entryPrice.toDouble()
            val risk = entry - p.initialStop.toDouble()
            return ChartLevels(price, entry, p.currentStop.toDouble(), entry + StrategyConfig().breakevenAfterR * risk)
        }
    }
}

private fun fmtPrice(d: Double): String = BigDecimal(d).round(MathContext(5)).stripTrailingZeros().toPlainString()

@Composable
fun PositionChartDialog(p: PositionEntity, ui: ChartUi, lastPrice: BigDecimal?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("${p.symbol} · velas de 4 horas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                when (ui) {
                    ChartUi.Loading -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is ChartUi.Error -> {
                        Text("No se pudo cargar el gráfico: ${ui.message}", color = Red)
                        TextButton(onClick = onRetry) { Text("Reintentar") }
                    }
                    is ChartUi.Ready -> {
                        val levels = ChartLevels.of(p, lastPrice?.toDouble() ?: ui.candles.last().close)
                        CandleChart(ui.candles, p.openedAtMs, levels, Modifier.fillMaxWidth().height(340.dp))
                        Spacer(Modifier.height(12.dp))
                        Legend(levels)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cerrar") }
            }
        }
    }
}

@Composable
private fun Legend(l: ChartLevels) {
    val fromPrice = { v: Double -> fmtPct((v / l.price - 1) * 100) }
    LegendRow(PriceWhite, "Precio actual", fmtPrice(l.price), "${fmtPct((l.price / l.entry - 1) * 100)} desde la entrada")
    LegendRow(EntryBlue, "Entrada", fmtPrice(l.entry), "línea vertical: momento de la compra")
    LegendRow(Red, "Stop actual", fmtPrice(l.stop), "${fromPrice(l.stop)} desde el precio: si baja hasta acá, vende")
    LegendRow(Green, "Objetivo 1,5R", fmtPrice(l.target), "${fromPrice(l.target)} desde el precio: el stop pasa a la entrada (sin riesgo)")
    Text(
        "No hay precio de venta fijo: después del objetivo el stop sigue subiendo detrás del precio para dejar correr la ganancia.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LegendRow(color: Color, label: String, value: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) { drawRect(color) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(value, fontWeight = FontWeight.Medium, color = color)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CandleChart(candles: List<Candle>, openedAtMs: Long, levels: ChartLevels, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Canvas(modifier) {
        val labelW = 56.dp.toPx()
        val chartW = size.width - labelW
        val h = size.height
        val dataHi = max(candles.maxOf { it.high }, max(levels.price, levels.entry))
        val dataLo = min(candles.minOf { it.low }, min(levels.stop, levels.price))
        // A target far above the candles would flatten them: then it is shown as an arrow at the top.
        val targetInside = levels.target <= dataHi + (dataHi - dataLo) * 0.5
        val hi = if (targetInside) max(dataHi, levels.target) else dataHi
        val pad = (hi - dataLo) * 0.06
        val top = hi + pad
        val bottom = dataLo - pad
        fun y(v: Double) = ((top - v) / (top - bottom) * h).toFloat()

        for (i in 1..3) drawLine(grid, Offset(0f, h * i / 4), Offset(chartW, h * i / 4), 1f)

        val step = chartW / candles.size
        val bodyW = step * 0.6f
        candles.forEachIndexed { i, c ->
            val x = step * i + step / 2
            val color = if (c.close >= c.open) Green else Red
            drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), 1.5f)
            val yTop = y(max(c.open, c.close))
            drawRect(color, Offset(x - bodyW / 2, yTop), Size(bodyW, max(1.5f, abs(y(c.open) - y(c.close)))))
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        val idx = ((openedAtMs - candles.first().openTime) / Interval.H4.millis).toInt()
        if (idx in candles.indices) {
            val x = step * idx + step / 2
            drawLine(EntryBlue, Offset(x, 0f), Offset(x, h), 2f, pathEffect = dash)
            val ye = y(levels.entry)
            drawPath(Path().apply { moveTo(x, ye - 10f); lineTo(x - 9f, ye - 24f); lineTo(x + 9f, ye - 24f); close() }, EntryBlue)
        }

        // Horizontal levels with value labels on the right, nudged apart so they never overlap.
        val lines = buildList {
            add(Triple(levels.stop, Red, fmtPrice(levels.stop)))
            add(Triple(levels.entry, EntryBlue, fmtPrice(levels.entry)))
            add(Triple(levels.price, PriceWhite, fmtPrice(levels.price)))
            if (targetInside) add(Triple(levels.target, Green, fmtPrice(levels.target)))
        }
        val measured = lines.map { (v, color, text) -> Triple(y(v), color, measurer.measure(text, labelStyle.copy(color = color))) }
        var lastBottom = Float.NEGATIVE_INFINITY
        measured.sortedBy { it.first }.forEach { (yy, color, m) ->
            drawLine(color, Offset(0f, yy), Offset(chartW, yy), 2f, pathEffect = if (color == PriceWhite) null else dash)
            val ty = max(yy - m.size.height / 2f, lastBottom).coerceIn(0f, h - m.size.height)
            drawText(m, topLeft = Offset(chartW + 6f, ty))
            lastBottom = ty + m.size.height
        }
        if (!targetInside) {
            val m = measurer.measure("↑ objetivo ${fmtPrice(levels.target)}", labelStyle.copy(color = Green))
            drawText(m, topLeft = Offset(8f, 4f))
        }
    }
}
