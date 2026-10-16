package com.trendpilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

/** Confirmation for destructive actions; [typed] requires typing an exact word. */
@Composable
fun ConfirmDialog(title: String, text: String, typed: String? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                if (typed != null) OutlinedTextField(input, { input = it }, label = { Text("Escribí $typed para confirmar") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, enabled = typed == null || input.trim() == typed) { Text("Confirmar", color = Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

fun fmtTime(ms: Long?): String = ms?.let { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"
fun fmtMoney(d: Double?): String = d?.let { String.format(Locale.US, "%,.2f", it) } ?: "—"
fun fmtPct(d: Double?): String = d?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—"
fun pnlColor(d: Double?): Color = when { d == null -> Color.Unspecified; d >= 0 -> Green; else -> Red }
fun fmtDuration(ms: Long): String {
    val h = ms / 3_600_000
    return if (h < 48) "${h}h" else "${h / 24}d ${h % 24}h"
}
