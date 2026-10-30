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
fun WalletManagerPage(balance: Long, info: WalletRpc.WalletInfoSnapshot?, onImport: () -> Unit, onSecurity: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Your wallet", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Manage this device’s Stohn wallet", color = DarkMuted, fontSize = 12.sp) }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E2030)), border = androidx.compose.foundation.BorderStroke(1.dp, Blue)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF0B3154)) { Icon(Icons.Default.AccountBalanceWallet, null, Modifier.padding(13.dp), tint = Electric) }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) { Text("Main Wallet", fontWeight = FontWeight.Bold); Text("${Amounts.format(balance)} STOHN", color = DarkMuted, fontSize = 12.sp) }
                    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0D3833)) { Text("ACTIVE", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Success, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        item { SettingCard("Wallet protection", if (info?.encrypted == true) "Encrypted with a passkey" else "Add a passkey to protect spending", Icons.Default.Shield, onSecurity) }
        item { SettingCard("Import wallet.dat", "Recover or migrate a Stohn Core wallet", Icons.Default.FileOpen, onImport) }
        item { Text("Your wallet is stored on this device and controlled by your local Stohn node.", color = DarkMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}


@Composable
fun ActivityPage(state: NodeController.State, txs: List<WalletRpc.WalletTransaction>, onTransaction: (WalletRpc.WalletTransaction) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val visible = txs.filter { filter == "All" || (filter == "Received" && it.amountSatoshis >= 0) || (filter == "Sent" && it.amountSatoshis < 0) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Text("Transactions", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("${state.peers} peers connected to your node", color = DarkMuted, fontSize = 12.sp) }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Received", "Sent").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue, selectedLabelColor = Color.White))
                }
            }
        }
        if (visible.isEmpty()) item { EmptyCard(if (txs.isEmpty()) "No transactions yet" else "No ${filter.lowercase()} transactions") }
        items(visible, key = { it.txid + it.time }) { tx -> TransactionRow(tx) { onTransaction(tx) } }
    }
}


@Composable
fun NodeStatusPage(state: NodeController.State, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1B28)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3549))) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Circle, null, tint = if (state.status == NodeController.Status.ERROR) Danger else Success, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(9.dp)); Text(if (state.status == NodeController.Status.READY) "Node Synced" else state.status.name.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                }
                Text(state.message, color = DarkMuted, fontSize = 12.sp)
                HorizontalDivider(color = Color(0xFF203244))
                NodeFact("Block Height", state.blocks.toString())
                NodeFact("Headers", state.headers.toString())
                NodeFact("Connections", "${state.peers} peers")
                NodeFact("Network", "Mainnet")
                NodeFact("Node", "Local Full Node")
                NodeFact("Sync", if (state.initialBlockDownload) "In progress" else "Up to date")
            }
        }
        if (state.status == NodeController.Status.ERROR) Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry node startup") }
    }
}


@Composable
fun NodeFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, color = DarkMuted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}


@Composable
fun SettingsPage(
    state: NodeController.State,
    info: WalletRpc.WalletInfoSnapshot?,
    onSecurity: () -> Unit,
    onImport: () -> Unit,
    onLock: () -> Unit,
    onNode: () -> Unit,
    onWallets: () -> Unit,
    onAddressBook: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Wallet and node preferences", color = DarkMuted, fontSize = 12.sp) }
        item { Text("PREFERENCES", color = Electric, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 8.dp)) }
        item { SettingCard("Security", "PIN, passkey and wallet protection", Icons.Default.Lock, onSecurity) }
        item { SettingCard("Node Status", "${state.status} · ${state.peers} peers", Icons.Default.Dns, onNode) }
        item { SettingCard("Wallet", if (info?.encrypted == true) "Encrypted wallet · Manage keys" else "Manage wallet and keys", Icons.Default.AccountBalanceWallet, onWallets) }
        item { SettingCard("Address book", "Saved addresses and their labels", Icons.Default.Contacts, onAddressBook) }
        item { SettingCard("Import wallet.dat", "Migrate with official Core tooling", Icons.Default.FileOpen, onImport) }
        item { SettingCard("Lock Wallet", "Remove the decryption key from memory", Icons.Default.Lock, onLock) }
        item { SettingCard("About Stohn", "Full node · Local RPC", Icons.Default.Info, {}) }
    }
}


@Composable
fun SecurityPage(info: WalletRpc.WalletInfoSnapshot?, onBackup: () -> Unit, onChangePasskey: () -> Unit, onLock: () -> Unit) {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var appLockEnabled by remember { mutableStateOf(AppAuthManager.isAppLockEnabled(context)) }
    var biometricEnabled by remember { mutableStateOf(AppAuthManager.isBiometricEnabled(context)) }
    var hasPin by remember { mutableStateOf(AppAuthManager.hasPin(context)) }

    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Security", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Protect access to your wallet", color = DarkMuted, fontSize = 12.sp) }
        item { Text("APP SECURITY", color = Electric, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 8.dp)) }
        item {
            SettingCard(
                "App Lock (PIN)",
                if (hasPin && appLockEnabled) "Enabled · PIN " + "•".repeat(4) else "Disabled",
                Icons.Default.Lock,
                onClick = { showPinDialog = true }
            )
        }
        item {
            SettingCard(
                "Biometric Unlock",
                if (biometricEnabled) "Enabled" else "Disabled",
                Icons.Default.Fingerprint,
                onClick = {
                    val enabled = !biometricEnabled
                    if (AppAuthManager.setBiometricEnabled(context, enabled)) {
                        biometricEnabled = enabled
                        Toast.makeText(context, if (enabled) "Biometric unlock enabled" else "Biometric unlock disabled", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Set an app-lock PIN before enabling biometric unlock", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        item { Text("WALLET SECURITY", color = Electric, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 8.dp)) }
        item { SettingCard("Wallet passkey", if (info?.encrypted == true) "Encrypted · Change passkey" else "Not encrypted · Add passkey", Icons.Default.Lock, onChangePasskey) }
        item { SettingCard("Backup Wallet", "Create or restore an encrypted backup", Icons.Default.Backup, onBackup) }
        item { SettingCard("Lock wallet", "Lock spending keys now", Icons.Default.Lock, onLock) }
        item { Text("Your wallet passkey is handled by Stohn Core on this device. Keep a separate, tested backup of your wallet file.", color = DarkMuted, fontSize = 11.sp, modifier = Modifier.padding(4.dp)) }
    }

    if (showPinDialog) {
        var pin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPinDialog = false },
            confirmButton = {
                Button(onClick = {
                    if (pin.length >= 4 && pin == confirmPin) {
                        AppAuthManager.setPin(context, pin)
                        hasPin = true
                        appLockEnabled = true
                        Toast.makeText(context, "PIN set successfully", Toast.LENGTH_SHORT).show()
                        showPinDialog = false
                        pin = ""
                        confirmPin = ""
                    } else {
                        Toast.makeText(context, "PINs must match and be at least 4 digits", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Set PIN") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (hasPin) {
                        AppAuthManager.clearPin(context)
                        hasPin = false
                        appLockEnabled = false
                        biometricEnabled = false
                        Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
                    }
                    showPinDialog = false
                    pin = ""
                    confirmPin = ""
                }) { Text(if (hasPin) "Disable" else "Cancel") }
            },
            title = { Text("App Lock PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6) pin = it },
                        label = { Text("Enter PIN (4-6 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6) confirmPin = it },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    )
                }
            }
        )
    }
}


@Composable
fun BackupPage(password: String, onPasswordChange: (String) -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Backup Wallet", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Export or restore an encrypted wallet backup", color = DarkMuted, fontSize = 12.sp) }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1722)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2F3E))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Backup password", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    OutlinedTextField(password, onPasswordChange, modifier = Modifier.fillMaxWidth(), label = { Text("Use a strong backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
                    Text("This password is separate from your wallet passkey. Keep it safe; it cannot be recovered.", color = DarkMuted, fontSize = 10.sp)
                }
            }
        }
        item {
            Button(onClick = onExport, enabled = password.length >= 8, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export Encrypted Backup")
            }
        }
        item {
            OutlinedButton(onClick = onRestore, enabled = password.length >= 8, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restore from Backup")
            }
        }
    }
}


@Composable
fun SecurityDialog(node: NodeController, info: WalletRpc.WalletInfoSnapshot?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val encrypted = info?.encrypted == true
    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {
        Button(onClick = {
            if (newPass.length < 8) { Toast.makeText(context, "Use at least 8 characters", Toast.LENGTH_SHORT).show(); return@Button }
            if (newPass != confirmPass) { Toast.makeText(context, "Passkeys do not match", Toast.LENGTH_SHORT).show(); return@Button }
            scope.launch {
                working = true
                try {
                    if (encrypted) node.changeWalletPassphrase(oldPass.toCharArray(), newPass.toCharArray())
                    else node.setWalletPassphrase(newPass.toCharArray())
                    Toast.makeText(context, if (encrypted) "Passkey changed" else "Wallet encrypted", Toast.LENGTH_LONG).show()
                    onDismiss()
                } catch (t: Throwable) {
                    Toast.makeText(context, "Wallet security failed: ${t.message}", Toast.LENGTH_LONG).show()
                } finally {
                    oldPass = ""
                    newPass = ""
                    confirmPass = ""
                    working = false
                }
            }
        }, enabled = !working) { Text(if (working) "Working…" else if (encrypted) "Change passkey" else "Set passkey") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, title = { Text(if (encrypted) "Change wallet passkey" else "Add wallet passkey", fontWeight = FontWeight.Black) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (encrypted) OutlinedTextField(oldPass, { oldPass = it }, Modifier.fillMaxWidth(), label = { Text("Current passkey") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(newPass, { newPass = it }, Modifier.fillMaxWidth(), label = { Text(if (encrypted) "New passkey" else "Passkey") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(confirmPass, { confirmPass = it }, Modifier.fillMaxWidth(), label = { Text("Confirm passkey") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("The passkey is sent only to the local Stohn Core RPC and is cleared from the UI state after the operation.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    })
}

suspend fun refresh(node: NodeController, apply: (Long, List<WalletRpc.WalletTransaction>, WalletRpc.WalletInfoSnapshot) -> Unit) {
    val info = node.walletInfo()
    apply(info.balanceSatoshis, node.transactions(), info)
}
