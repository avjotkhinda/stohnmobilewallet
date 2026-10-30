package org.stohncoin.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.R
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.wallet.AddressBook
import org.stohncoin.wallet.wallet.Amounts
import org.stohncoin.wallet.wallet.WalletRpc
import org.stohncoin.wallet.security.AppAuthManager
import java.io.File

@Composable
fun TransactionRow(tx: WalletRpc.WalletTransaction, onClick: () -> Unit) {
    val received = tx.amountSatoshis >= 0
    val timestamp = remember(tx.time) {
        runCatching { java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(tx.time * 1000)) }.getOrDefault("")
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF091722)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D3040))
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = if (received) Color(0xFF0C332D) else Color(0xFF3A2427)) {
                Icon(if (received) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, Modifier.padding(9.dp).size(18.dp), tint = if (received) Success else Danger)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (received) "Received" else "Sent", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(timestamp.ifBlank { tx.txid.take(12) + "…" }, color = DarkMuted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (received) "+" else "") + Amounts.format(tx.amountSatoshis) + " STOHN", color = if (received) Success else Danger, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("${tx.confirmations} confirmations", color = DarkMuted, fontSize = 9.sp)
            }
        }
    }
}


@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = DarkMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}


@Composable
fun TransactionDetailPage(tx: WalletRpc.WalletTransaction?) {
    val context = LocalContext.current
    if (tx == null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) { EmptyCard("Transaction not found") }
        return
    }
    val received = tx.amountSatoshis >= 0
    val book = remember(context) { AddressBook.initialize(File(context.filesDir, ADDRESS_BOOK_DIR)) }
    var addressLabel by remember(tx.address) { mutableStateOf("") }
    LaunchedEffect(tx.address) {
        addressLabel = if (tx.address.isBlank()) "" else withContext(Dispatchers.IO) { book.labelFor(tx.address) }
    }
    val timestamp = remember(tx.time) {
        runCatching {
            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(tx.time * 1000))
        }.getOrDefault("Unknown")
    }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1824)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF203548))) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (received) "Received" else "Sent", color = DarkMuted, fontSize = 12.sp)
                    Text((if (received) "+" else "") + Amounts.format(tx.amountSatoshis) + " STOHN", color = if (received) Success else Danger, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(timestamp, color = DarkMuted, fontSize = 11.sp)
                }
            }
        }
        item { DetailCard("Status", if (tx.confirmations > 0) "Confirmed · ${tx.confirmations} confirmations" else "Unconfirmed") }
        item { DetailCard("Amount", Amounts.formatFixed(tx.amountSatoshis) + " STOHN") }
        item { DetailCard("Fee", if (tx.feeSatoshis == 0L) "Included in transaction" else Amounts.formatFixed(tx.feeSatoshis) + " STOHN") }
        if (tx.address.isNotBlank()) item {
            DetailCard(if (addressLabel.isBlank()) "Address" else "Address · $addressLabel", tx.address)
        }
        item { DetailCard("Type", tx.category) }
        item { DetailCard("Transaction ID", tx.txid) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, tx.txid) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Copy txid")
                }
                if (tx.address.isNotBlank()) OutlinedButton(onClick = { copy(context, tx.address) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Copy address")
                }
            }
        }
    }
}


@Composable
fun DetailCard(label: String, value: String) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF091722)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D3040))) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, color = DarkMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), overflow = TextOverflow.Ellipsis)
        }
    }
}
