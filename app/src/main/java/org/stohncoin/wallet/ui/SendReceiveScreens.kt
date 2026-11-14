package org.stohncoin.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import org.stohncoin.wallet.core.SendGateway
import org.stohncoin.wallet.wallet.AddressBook
import org.stohncoin.wallet.wallet.AddressBookLabel
import org.stohncoin.wallet.wallet.Amounts
import org.stohncoin.wallet.wallet.WalletRpc
import org.stohncoin.wallet.security.AddressBookKey
import org.stohncoin.wallet.security.AppAuthManager
import java.io.File

@Composable
fun SendPage(
    node: SendGateway,
    info: WalletRpc.WalletInfoSnapshot?,
    balance: Long,
    busy: Boolean,
    initialAddress: String = "",
    onAddressBook: () -> Unit,
    onBusy: (Boolean) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    val addressBookDir = remember { File(context.filesDir, "addressbook") }
    val addressBookKey = remember { AddressBookKey.get(context) }
    // The label field always follows the address being paid to, so a saved name shows up as soon
    // as the recipient is entered or scanned.
    LaunchedEffect(address) {
        val trimmed = address.trim()
        label = withContext(Dispatchers.IO) { AddressBook.load(addressBookDir, addressBookKey).labelFor(trimmed) } ?: ""
    }
    // The passkey must never be persisted into saved state (it would outlive the process and
    // leak into the Activity's saved-state bundle), so it lives in a plain remember holder.
    var passphrase by remember { mutableStateOf("") }
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
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Send STOHN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Send from your local Stohn wallet", color = DarkMuted, fontSize = 12.sp)
                }
                TextButton(onClick = onAddressBook) {
                    Icon(Icons.Default.Person, null, Modifier.size(16.dp), tint = Electric)
                    Spacer(Modifier.width(6.dp))
                    Text("Address book", color = Electric, fontSize = 12.sp)
                }
            }
        }
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
                value = label,
                onValueChange = { label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Label") },
                placeholder = { Text("Name this address (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp)
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
                            .onFailure { toast(context, "Fee estimate failed: ${it.message}") }
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
            onDismissRequest = { showConfirm = false; passphrase = "" },
            title = { Text("Confirm transaction", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECIPIENT", color = DarkMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(address, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (label.isNotBlank()) Text(label, fontSize = 12.sp, color = Electric, fontWeight = FontWeight.SemiBold)
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
                            runCatching { withContext(Dispatchers.IO) { persistLabel(addressBookDir, address.trim(), label, addressBookKey) } }
                            toast(context, "Transaction sent")
                            passphrase = ""
                            onComplete()
                        } catch (t: Throwable) {
                            toast(context, "Send failed: ${t.message}")
                        } finally {
                            passphrase = ""
                            onBusy(false)
                        }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false; passphrase = "" }) { Text("Cancel") } }
        )
    }
}


/**
 * Searchable address book backed by the same `labels.json` the send screen writes, so everything
 * here travels inside the encrypted backup container.
 */
@Composable
fun AddressBookPage(onSend: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val addressBookDir = remember { File(context.filesDir, "addressbook") }
    val addressBookKey = remember { AddressBookKey.get(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf(AddressBook.load(addressBookDir, addressBookKey).labels) }
    var draftAddress by rememberSaveable { mutableStateOf("") }
    var draftLabel by rememberSaveable { mutableStateOf("") }
    var editingRaw by rememberSaveable { mutableStateOf("") }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AddressBookLabel?>(null) }

    fun write(next: List<AddressBookLabel>) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { AddressBook.save(addressBookDir, next, addressBookKey) } }
            saved.onFailure { toast(context, "Could not save the address book", short = true) }
            entries = withContext(Dispatchers.IO) { runCatching { AddressBook.load(addressBookDir, addressBookKey).labels }.getOrElse { emptyList() } }
        }
    }

    val visible = remember(entries, query) {
        val needle = query.trim().lowercase()
        entries
            .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.raw.lowercase().contains(needle) }
            .sortedBy { it.label.lowercase() }
    }
    val renaming = showEditor && editingRaw.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search") },
            placeholder = { Text("Name or address") },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search", tint = DarkMuted) }
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("${visible.size} saved", color = DarkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { editingRaw = ""; draftAddress = ""; draftLabel = ""; showEditor = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Electric)
                Spacer(Modifier.width(4.dp))
                Text("Add", color = Electric, fontSize = 12.sp)
            }
        }
        when {
            visible.isEmpty() && entries.isEmpty() -> EmptyCard("No saved names yet. A name is saved when you send, or add one here.")
            visible.isEmpty() -> EmptyCard("Nothing matches \"${query.trim()}\"")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(visible, key = { it.raw }) { entry ->
                    AddressBookRow(
                        entry = entry,
                        onSend = { onSend(entry.raw) },
                        onRename = {
                            draftAddress = entry.raw
                            draftLabel = entry.label
                            editingRaw = entry.raw
                            showEditor = true
                        },
                        onDelete = { pendingDelete = entry }
                    )
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            containerColor = DarkSurface2,
            title = { Text(if (renaming) "Rename contact" else "Add contact", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!renaming) OutlinedTextField(
                        value = draftAddress,
                        onValueChange = { draftAddress = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Address") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = draftLabel,
                        onValueChange = { draftLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val addr = draftAddress.trim()
                    val name = draftLabel.trim()
                    when {
                        name.isEmpty() -> toast(context, "Enter a name for this address", short = true)
                        renaming -> {
                            val raw = editingRaw
                            write(entries.map { if (it.raw == raw) AddressBookLabel(raw, name) else it })
                            showEditor = false
                        }
                        addr.isEmpty() -> toast(context, "Enter an address", short = true)
                        else -> {
                            write(entries.filter { it.raw != addr } + AddressBookLabel(addr, name))
                            showEditor = false
                        }
                    }
                }) { Text("Save", color = Electric) }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = DarkSurface2,
            title = { Text("Remove ${target.label}?", fontWeight = FontWeight.Bold) },
            text = { Text("${target.raw} is removed from your address book only. Wallet and coins are untouched.", fontSize = 12.sp, color = DarkMuted) },
            confirmButton = {
                TextButton(onClick = { write(entries.filterNot { it.raw == target.raw }); pendingDelete = null }) { Text("Remove", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}


@Composable
private fun AddressBookRow(entry: AddressBookLabel, onSend: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1722)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2F3E))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.raw, color = DarkMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            IconButton(onClick = onSend, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.NorthEast, "Send to ${entry.label}", tint = Electric, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { copy(context, entry.raw) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy address", tint = DarkMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, "Rename", tint = DarkMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.DeleteOutline, "Remove", tint = Danger, modifier = Modifier.size(18.dp))
            }
        }
    }
}


/** Saves the label typed on the send screen; clearing the field removes the saved entry. */
private fun persistLabel(dir: File, address: String, label: String, key: javax.crypto.SecretKey? = null) {
    if (address.isBlank()) return
    val trimmed = label.trim()
    val others = AddressBook.load(dir, key).labels.filter { it.raw != address }
    val updated = if (trimmed.isEmpty()) others else others + AddressBookLabel(address, trimmed)
    AddressBook.save(dir, updated, key)
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
                                toast(context, "Unable to build the QR image", short = true)
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
