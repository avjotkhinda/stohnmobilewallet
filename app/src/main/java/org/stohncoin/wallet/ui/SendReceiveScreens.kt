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
import kotlinx.coroutines.launch
import org.stohncoin.wallet.R
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.wallet.Amounts
import org.stohncoin.wallet.wallet.WalletRpc
import org.stohncoin.wallet.security.AppAuthManager
import java.io.File

@Composable
fun SendPage(
    node: NodeController,
    info: WalletRpc.WalletInfoSnapshot?,
    balance: Long,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var subtractFee by rememberSaveable { mutableStateOf(false) }
    var estimate by remember { mutableStateOf<WalletRpc.FeeEstimate?>(null) }
    var feePreset by rememberSaveable { mutableStateOf(FeePreset.Automatic.name) }
    var showConfirm by remember { mutableStateOf(false) }
    val confTarget = FeePreset.of(feePreset).confTarget
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val raw = contents.trim()
        if (raw.isEmpty()) return@rememberLauncherForActivityResult
        val parsed = if (raw.contains(":")) {
            val withoutScheme = raw.substringAfterLast(":")
            withoutScheme.substringBefore("?").substringBefore("&")
        } else {
            raw
        }
        if (parsed.isNotBlank()) {
            address = parsed
            estimate = null
        }
        val amountMatch = Regex("""amount=([0-9]*\.?[0-9]+)""").find(raw)
        amountMatch?.groupValues?.getOrNull(1)?.let {
            amountText = it
            estimate = null
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(15.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
        item { Text("Send STOHN", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Send from your local Stohn wallet", color = DarkMuted, fontSize = 12.sp) }
        item {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; estimate = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("To") },
                placeholder = { Text("Enter address or scan QR") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        val options = ScanOptions()
                        options.setPrompt("Scan a Stohn address")
                        options.setBeepEnabled(true)
                        options.setOrientationLocked(false)
                        scanLauncher.launch(options)
                    }) {
                        Icon(Icons.Default.QrCodeScanner, "Scan QR", tint = Electric)
                    }
                }
            )
        }
        item {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' }; estimate = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount") },
                placeholder = { Text("STOHN") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                trailingIcon = { TextButton(onClick = { if (balance > 0L) amountText = Amounts.format(balance); estimate = null }) { Text("Max", color = Electric) } }
            )
        }
        item { Text("Available: ${Amounts.formatFixed(balance)} STOHN", color = DarkMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)) }
        item {
            Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1722)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2F3E))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, null, tint = Electric)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text("Network Fee", fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text(FeePreset.of(feePreset).label, color = DarkMuted, fontSize = 10.sp) }
                    }
                    listOf(FeePreset.entries.take(2), FeePreset.entries.drop(2)).forEach { chunk ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunk.forEach { preset ->
                                FilterChip(
                                    selected = feePreset == preset.name,
                                    onClick = { feePreset = preset.name; estimate = null },
                                    label = { Text(preset.name, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = subtractFee, onCheckedChange = { subtractFee = it; estimate = null }, colors = CheckboxDefaults.colors(checkedColor = Blue))
                Text("Subtract fee from amount", color = DarkMuted, fontSize = 12.sp)
            }
        }
        if (info?.encrypted == true) item {
            OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Wallet passkey") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(15.dp))
        }
        item {
            OutlinedButton(
                onClick = {
                    val amount = Amounts.parse(amountText)
                    if (amount == null || amount <= 0L || address.isBlank()) return@OutlinedButton
                    scope.launch {
                        onBusy(true)
                        runCatching { estimate = node.estimateFee(address, amount, subtractFee, confTarget) }
                            .onFailure { Toast.makeText(context, "Fee estimate failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        onBusy(false)
                    }
                },
                enabled = !busy && address.isNotBlank() && (Amounts.parse(amountText) ?: 0L) > 0L,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (estimate == null) "Estimate Fee" else "Update Fee Estimate") }
        }
        estimate?.let { fee -> item { FeeSummary(fee, subtractFee) } }
        item {
            Button(
                onClick = { showConfirm = true },
                enabled = !busy && estimate != null && address.isNotBlank() && (Amounts.parse(amountText) ?: 0L) > 0L && (info?.encrypted != true || passphrase.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Review & Send", fontWeight = FontWeight.Bold) }
        }
        item { Text("Only send STOHN to a STOHN address. Transactions cannot be reversed.", color = DarkMuted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }

    if (showConfirm) {
        val confirmAmount = Amounts.parse(amountText) ?: 0L
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Confirm transaction", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECIPIENT", color = DarkMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(address, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = Color(0xFF1D2F3E))
                    DetailRow("Amount", Amounts.formatFixed(confirmAmount) + " STOHN")
                    DetailRow("Network fee", estimate?.let { Amounts.formatFixed(it.feeSatoshis) + " STOHN" } ?: "Estimated above")
                    DetailRow("Wallet total", estimate?.let { Amounts.formatFixed(it.totalSatoshis) + " STOHN" } ?: "—")
                    DetailRow("Fee target", FeePreset.of(feePreset).name)
                    Text("Check the first and last characters of the recipient address. Transactions cannot be reversed.", color = DarkMuted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amount = Amounts.parse(amountText) ?: return@Button
                    showConfirm = false
                    scope.launch {
                        onBusy(true)
                        try {
                            node.send(address, amount, if (info?.encrypted == true) passphrase.toCharArray() else null, subtractFee, confTarget)
                            Toast.makeText(context, "Transaction sent", Toast.LENGTH_LONG).show()
                            passphrase = ""
                            onComplete()
                        } catch (t: Throwable) {
                            Toast.makeText(context, "Send failed: ${t.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            passphrase = ""
                            onBusy(false)
                        }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
        )
    }
}


/** Fee choices map to Core confirmation targets; Automatic keeps Core's own default path. */
enum class FeePreset(val label: String, val confTarget: Int?) {
    Automatic("Automatic · estimated by your node", null),
    Economy("Economy · about 6 blocks", 6),
    Normal("Normal · about 3 blocks", 3),
    Priority("Priority · about 1 block", 1);

    companion object {
        fun of(name: String): FeePreset = entries.firstOrNull { it.name == name } ?: Automatic
    }
}


@Composable
fun ReceivePage(address: String, onCopy: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(address) { qr(address, 720) }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Receive STOHN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Share this address to receive funds", color = DarkMuted, fontSize = 12.sp)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1824)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF203548))) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), "Stohn receive QR code", Modifier.size(238.dp)) }
                Text(address, fontSize = 12.sp, color = DarkText, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Copy")
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, address)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Stohn address"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Share")
                    }
                    OutlinedButton(
                        onClick = {
                            val png = bitmap?.let { b -> shareableQrImage(context, b) }
                            if (png == null) {
                                Toast.makeText(context, "Unable to build the QR image", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", png
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Stohn QR code"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Default.QrCode2, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("QR Image")
                    }
                }
            }
        }
        Text("Use only this address for STOHN. Check the address before sharing.", color = DarkMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}


@Composable
fun FeeSummary(fee: WalletRpc.FeeEstimate, subtract: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Estimated transaction fee", fontWeight = FontWeight.Bold)
            Text("Fee: ${Amounts.formatFixed(fee.feeSatoshis)} STOHN")
            Text("Recipient receives: ${Amounts.formatFixed(fee.recipientSatoshis)} STOHN")
            Text("Wallet deducted: ${Amounts.formatFixed(fee.totalSatoshis)} STOHN")
            Text(if (subtract) "Fee is taken from the entered amount." else "Fee is added to the wallet deduction.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}
