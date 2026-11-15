package org.stohncoin.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.wallet.WalletRpc
import java.text.DecimalFormat

@Composable
fun CoinsPage(
    node: org.stohncoin.wallet.core.NodeController? = null,
    onSendSelected: (List<WalletRpc.Utxo>) -> Unit = {}
) {
    var utxos by remember { mutableStateOf<List<WalletRpc.Utxo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(node) {
        loading = true
        error = null
        if (node == null) { loading = false; return@LaunchedEffect }
        try {
            utxos = withContext(Dispatchers.IO) { node.unspent() }
        } catch (t: Throwable) {
            error = t.message ?: "Could not load coins"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Coins (unspent outputs)", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
        if (loading) {
            Text("Loading...", color = Color(0xFF9CA3AF), fontSize = 13.sp)
            return@Column
        }
        error?.let {
            Text(it, color = Color(0xFFFF665F), fontSize = 13.sp)
            return@Column
        }
        if (utxos.isEmpty()) {
            Text("No spendable coins found.", color = Color(0xFF9CA3AF), fontSize = 13.sp)
            return@Column
        }
        val total = utxos.sumOf { it.amountSatoshis }
        val selectedUtxos = utxos.filter { selected.contains("${it.txid}:${it.vout}") }
        val selectedTotal = selectedUtxos.sumOf { it.amountSatoshis }
        Text("Total: ${formatCoin(total)} STOHN across ${utxos.size} output(s)", color = Color(0xFF9CA3AF), fontSize = 12.sp)
        if (utxos.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = utxos.filter { it.spendable }.map { "${it.txid}:${it.vout}" }.toSet() }) { Text("Select all") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
            }
        }
        if (selectedUtxos.isNotEmpty()) {
            Text("Selected: ${formatCoin(selectedTotal)} STOHN across ${selectedUtxos.size} output(s)", color = Color(0xFF4F9BFF), fontSize = 12.sp)
            Button(
                onClick = { onSendSelected(selectedUtxos) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send from selected coins") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(utxos) { u ->
                val key = "${u.txid}:${u.vout}"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1824))
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected.contains(key),
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + key else selected - key
                            },
                            enabled = u.spendable
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatCoin(u.amountSatoshis) + " STOHN", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("${u.confirmations} conf", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                            }
                            Text(u.address, color = Color(0xFF4F9BFF), fontSize = 11.sp)
                            Text("${u.txid.take(12)}...:${u.vout}", color = Color(0xFF6B7280), fontSize = 10.sp)
                            if (!u.spendable) Text("Not spendable", color = Color(0xFFFF665F), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatCoin(satoshis: Long): String {
    val whole = satoshis / 100_000_000L
    val frac = (satoshis % 100_000_000L).toString().padStart(8, '0').trimEnd('0')
    return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
}
