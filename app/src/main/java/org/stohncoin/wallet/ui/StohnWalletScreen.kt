package org.stohncoin.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch
import org.stohncoin.wallet.R
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.wallet.WalletRpc
import java.io.File
import kotlin.math.roundToInt

private val DarkBg = Color(0xFF030A18)
private val DarkSurface = Color(0xFF0A1930)
private val DarkSurface2 = Color(0xFF0D2444)
private val DarkText = Color(0xFFF4F8FF)
private val DarkMuted = Color(0xFFB7C8DF)
private val Blue = Color(0xFF1687FF)
private val Electric = Color(0xFF62C8FF)
private val LightBg = Color(0xFFF3F8FF)
private val LightSurface = Color.White
private val LightText = Color(0xFF10213A)
private val LightMuted = Color(0xFF53657D)

private val DarkScheme = darkColorScheme(
    primary = Blue,
    secondary = Electric,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkMuted
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF075EC4),
    secondary = Color(0xFF087EBA),
    background = LightBg,
    surface = LightSurface,
    onBackground = LightText,
    onSurface = LightText,
    onSurfaceVariant = LightMuted
)

@Composable
fun StohnWalletScreen(node: NodeController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("stohn_ui", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    val state by node.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var balance by remember { mutableDoubleStateOf(0.0) }
    var transactions by remember { mutableStateOf(emptyList<WalletRpc.WalletTransaction>()) }
    var walletInfo by remember { mutableStateOf<WalletRpc.WalletInfoSnapshot?>(null) }
    var receiveAddress by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val staged = File(context.cacheDir, "wallet-import-${System.currentTimeMillis()}.dat")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_IMPORT_BYTES) { "wallet.dat is too large" }
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                } ?: error("Unable to read wallet.dat")
                val info = node.inspectWalletDat(staged)
                val destination = File(context.filesDir, "fullmode/stohn-data/migrated-wallet")
                require(!destination.exists()) { "A migrated wallet already exists" }
                node.importWalletDat(staged, destination)
                Toast.makeText(context, "Imported ${info.format} wallet.dat", Toast.LENGTH_LONG).show()
                staged.delete()
                refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi }
            }.onFailure {
    Toast.makeText(context, "Import failed. Check the wallet file and try again.", Toast.LENGTH_LONG).show()
}
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        node.start()
    }
    LaunchedEffect(state.status) {
        if (state.status == NodeController.Status.READY || state.status == NodeController.Status.SYNCING) {
            runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } }
        }
    }

    MaterialTheme(colorScheme = if (darkMode) DarkScheme else LightScheme) {
        val background by animateColorAsState(if (darkMode) DarkBg else LightBg, tween(500), label = "background")
        Box(Modifier.fillMaxSize().background(background)) {
            AnimatedBackdrop(darkMode)
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopBar(
                        darkMode = darkMode,
                        onToggleTheme = {
                            darkMode = !darkMode
                            prefs.edit().putBoolean("dark_mode", darkMode).apply()
                        },
                        onSecurity = { dialog = DialogKind.Security },
                        onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                    )
                },
                bottomBar = { NavigationBar(containerColor = if (darkMode) Color(0xE6091427) else Color(0xEFFFFFFF)) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.AccountBalanceWallet, null) }, label = { Text("Wallet") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                } }
            ) { padding ->
               Box(Modifier.padding(padding)) {
                  val page = tab
                    when (page) {
                        0 -> WalletHome(
                            state = state,
                            balance = balance,
                            onSend = { dialog = DialogKind.Send },
                            onReceive = {
                                scope.launch {
                                    runCatching { receiveAddress = node.newAddress(); dialog = DialogKind.Receive }
                                        .onFailure {
    Toast.makeText(context, "Unable to create a receiving address.", Toast.LENGTH_LONG).show()
}
                                }
                            },
                            onRefresh = {
                                scope.launch { busy = true; runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } }.onFailure {
    Toast.makeText(context, "Refresh failed. Please try again.", Toast.LENGTH_LONG).show()
}; busy = false }
                            }
                        )
                        1 -> ActivityPage(state, transactions)
                        else -> SettingsPage(state, walletInfo, onSecurity = { dialog = DialogKind.Security }, onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }, onLock = { scope.launch { runCatching { node.lockWallet() } } })
                    }
                }
            }
            AnimatedVisibility(visible = busy, modifier = Modifier.align(Alignment.Center), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Surface(shape = RoundedCornerShape(24.dp), color = if (darkMode) DarkSurface2 else LightSurface, tonalElevation = 8.dp) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Working…", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        when (val d = dialog) {
            DialogKind.Send -> SendDialog(node, walletInfo, onDismiss = { dialog = null }, onSent = {
                dialog = null
                scope.launch { runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } } }
            })
            DialogKind.Receive -> ReceiveDialog(receiveAddress, onDismiss = { dialog = null }, onCopy = { copy(context, receiveAddress) })
            DialogKind.Security -> SecurityDialog(node, walletInfo, onDismiss = { dialog = null })
            null -> Unit
        }
    }
}

private enum class DialogKind { Send, Receive, Security }

@Composable
private fun AnimatedBackdrop(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "nebula")
    val pulse by transition.animateFloat(0.82f, 1.14f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().alpha(if (dark) .22f else .10f).scale(pulse).background(Brush.radialGradient(listOf(Blue, Color.Transparent), radius = 720f)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(darkMode: Boolean, onToggleTheme: () -> Unit, onSecurity: () -> Unit, onImport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        navigationIcon = { Image(painterResource(R.drawable.stohn_logo), "Stohn", Modifier.size(42.dp), contentScale = ContentScale.Fit) },
        title = { Text("STOHN", fontWeight = FontWeight.Black, letterSpacing = 4.sp) },
        actions = {
            IconButton(onClick = onToggleTheme) { Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Wallet security") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; onSecurity() })
                    DropdownMenuItem(text = { Text("Import wallet.dat") }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { menu = false; onImport() })
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
private fun WalletHome(state: NodeController.State, balance: Double, onSend: () -> Unit, onReceive: () -> Unit, onRefresh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("FUTURE WALLET", color = Electric, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Your SOH", fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        item {
            val shimmer = rememberInfiniteTransition(label = "card")
            val alpha by shimmer.animateFloat(.92f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "alpha")
            Card(Modifier.fillMaxWidth().alpha(alpha), shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.stohn_logo), "Stohn logo", Modifier.size(46.dp))
                        Spacer(Modifier.width(12.dp))
                        Column { Text("STOHNCOIN", fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("FULL MODE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("%.8f SOH".format(balance), fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    Text("Live wallet balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FuturisticButton("Send", Icons.Default.ArrowUpward, onSend, Modifier.weight(1f))
                        FuturisticButton("Receive", Icons.Default.ArrowDownward, onReceive, Modifier.weight(1f))
                        FuturisticButton("Refresh", Icons.Default.Sync, onRefresh, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusCard("NODE", state.status.name, Modifier.weight(1f))
                StatusCard("PEERS", state.peers.toString(), Modifier.weight(1f))
                StatusCard("BLOCKS", state.blocks.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row { Text("Full Node", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(state.status.name, color = Electric, fontSize = 12.sp) }
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Electric)
                    Text("Headers ${state.headers} • Blocks ${state.blocks}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FuturisticButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
        Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StatusCard(title: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) { Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
    }
}

@Composable
private fun ActivityPage(state: NodeController.State, txs: List<WalletRpc.WalletTransaction>) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Activity", fontSize = 30.sp, fontWeight = FontWeight.Black); Text("${state.message} • ${state.peers} peers", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (txs.isEmpty()) item { EmptyCard("No wallet transactions yet") }
        items(txs, key = { it.txid + it.time }) { tx ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tx.amount >= 0) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, tint = if (tx.amount >= 0) Electric else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(tx.category.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold); Text(tx.txid.take(16) + "…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                    Text("%+.8f".format(tx.amount), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: NodeController.State, info: WalletRpc.WalletInfoSnapshot?, onSecurity: () -> Unit, onImport: () -> Unit, onLock: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Black); Text("Wallet and Full Node controls", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingCard("Wallet security", if (info?.encrypted == true) "Encrypted" else "Not encrypted", Icons.Default.Lock, onSecurity) }
        item { SettingCard("Import wallet.dat", "Migrate using official Stohn Core tooling", Icons.Default.FileOpen, onImport) }
        item { SettingCard("Lock wallet", "Remove the decryption key from memory", Icons.Default.Lock, onLock) }
        item { SettingCard("Node", "${state.status} • ${state.peers} peers • ${state.blocks} blocks", Icons.Default.Dns, {}) }
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(28.dp), tint = Electric); Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } }
    }
}

@Composable
private fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun SendDialog(node: NodeController, info: WalletRpc.WalletInfoSnapshot?, onDismiss: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var subtract by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf<WalletRpc.FeeEstimate?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, title = { Text("Send SOH", fontWeight = FontWeight.Black) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(address, { address = it; fee = null }, Modifier.fillMaxWidth(), label = { Text("Recipient address") }, singleLine = true)
            OutlinedTextField(amountText, { amountText = it; fee = null }, Modifier.fillMaxWidth(), label = { Text("Amount (SOH)") }, singleLine = true)
            Text("Fee payment", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(subtract, { subtract = true; fee = null }); Text("Subtract fee from amount") }
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!subtract, { subtract = false; fee = null }); Text("Pay fee separately from wallet") }
            OutlinedButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                if (amount == null || amount <= 0.0) Toast.makeText(context, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                else scope.launch { working = true; runCatching { fee = node.estimateFee(address, amount, subtract) }.onFailure { Toast.makeText(context, "Fee estimate failed: ${it.message}", Toast.LENGTH_LONG).show() }; working = false }
            }, enabled = !working && address.isNotBlank() && amountText.toDoubleOrNull()?.let { it > 0 } == true, modifier = Modifier.fillMaxWidth()) { Text(if (working) "Estimating…" else "Estimate transaction fee") }
            fee?.let { f ->
                FeeSummary(f, subtract)
                if (info?.encrypted == true) OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Wallet passkey") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@Button
                    scope.launch {
                        working = true
                        try {
                            node.send(address, amount, if (info?.encrypted == true) passphrase.toCharArray() else null, subtract)
                            Toast.makeText(context, "Transaction sent", Toast.LENGTH_LONG).show()
                            onSent()
                        } catch (t: Throwable) {
                            Toast.makeText(context, "Send failed: ${t.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            passphrase = ""
                            working = false
                        }
                    }
                }, enabled = !working && address.isNotBlank() && amountText.toDoubleOrNull()?.let { it > 0 } == true && (info?.encrypted != true || passphrase.isNotEmpty()), modifier = Modifier.weight(1f)) { Text(if (working) "Sending…" else "Send") }
            }
        }
    })
}

@Composable
private fun FeeSummary(fee: WalletRpc.FeeEstimate, subtract: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Estimated transaction fee", fontWeight = FontWeight.Bold)
            Text("Fee: %.8f SOH".format(fee.fee))
            Text("Recipient receives: %.8f SOH".format(fee.recipientAmount))
            Text("Wallet deducted: %.8f SOH".format(fee.total))
            Text(if (subtract) "Fee is taken from the entered amount." else "Fee is added to the wallet deduction.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ReceiveDialog(address: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    val bitmap = remember(address) { qr(address, 720) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { Button(onClick = onCopy) { Text("Copy address") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }, title = { Text("Receive SOH", fontWeight = FontWeight.Black) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bitmap?.let { Image(it.asImageBitmap(), "QR address", Modifier.size(230.dp)) }
            Text(address, textAlign = TextAlign.Center, fontSize = 12.sp)
        }
    })
}

@Composable
private fun SecurityDialog(node: NodeController, info: WalletRpc.WalletInfoSnapshot?, onDismiss: () -> Unit) {
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

private suspend fun refresh(node: NodeController, apply: (Double, List<WalletRpc.WalletTransaction>, WalletRpc.WalletInfoSnapshot) -> Unit) {
    val info = node.walletInfo()
    apply(info.balance, node.transactions(), info)
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Stohn address", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun qr(text: String, size: Int): android.graphics.Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}.getOrNull()

private const val MAX_IMPORT_BYTES = 256L * 1024 * 1024
