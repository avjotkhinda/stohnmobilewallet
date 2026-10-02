package org.stohncoin.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.launch
import org.stohncoin.wallet.R
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.wallet.WalletRpc
import java.io.File

private val DarkBg = Color(0xFF030A18)
private val DarkSurface = Color(0xFF091522)
private val DarkSurface2 = Color(0xFF102235)
private val DarkText = Color(0xFFF4F8FF)
private val DarkMuted = Color(0xFFB7C8DF)
private val Blue = Color(0xFF1687FF)
private val Electric = Color(0xFF62C8FF)
private val Success = Color(0xFF31D49A)
private val Danger = Color(0xFFFF665F)
private val DarkScheme = darkColorScheme(
    primary = Blue,
    secondary = Electric,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkMuted
)

@Composable
fun StohnWalletScreen(node: NodeController) {
    val context = LocalContext.current
    val state by node.state.collectAsState()
    var route by rememberSaveable { mutableStateOf("home") }
    var balance by remember { mutableDoubleStateOf(0.0) }
    var transactions by remember { mutableStateOf(emptyList<WalletRpc.WalletTransaction>()) }
    var walletInfo by remember { mutableStateOf<WalletRpc.WalletInfoSnapshot?>(null) }
    var receiveAddress by remember { mutableStateOf("") }
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rootRoutes = setOf("home", "wallets", "activity", "settings")
    BackHandler(enabled = route !in rootRoutes) {
        route = when (route) { "security", "node" -> "settings"; "backup" -> "security"; else -> "home" }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val staged = File(context.cacheDir, "wallet-import-${System.currentTimeMillis()}.dat")
            try {
                runCatching {
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
                    refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi }
                }.onFailure {
                    Toast.makeText(context, "Import failed: ${it.message ?: "wallet could not be migrated"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                staged.delete()
                busy = false
            }
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val encryptedFile = File(context.cacheDir, "stohn-backup-${System.currentTimeMillis()}.stohnbackup")
            busy = true
            try {
                node.createWalletBackup(encryptedFile, backupPassword.toCharArray())
                context.contentResolver.openOutputStream(uri)?.use { output -> encryptedFile.inputStream().use { it.copyTo(output) } }
                    ?: error("Unable to write the backup file")
                Toast.makeText(context, "Encrypted wallet backup saved", Toast.LENGTH_LONG).show()
                backupPassword = ""
            } catch (t: Throwable) {
                Toast.makeText(context, "Backup failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                encryptedFile.delete()
                backupPassword = ""
                busy = false
            }
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = File(context.cacheDir, "stohn-backup-import-${System.currentTimeMillis()}.stohnbackup")
            val destination = File(context.filesDir, "fullmode/stohn-data/restored-wallet")
            busy = true
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_BACKUP_BYTES) { "Backup file is too large" }
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                } ?: error("Unable to read the backup file")
                require(backupPassword.isNotBlank()) { "Enter the backup password first" }
                require(!destination.exists()) { "A restored wallet already exists" }
                node.restoreWalletBackup(staged, backupPassword.toCharArray(), destination)
                refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi }
                Toast.makeText(context, "Wallet restored successfully", Toast.LENGTH_LONG).show()
                backupPassword = ""
                route = "home"
            } catch (t: Throwable) {
                Toast.makeText(context, "Restore failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                staged.delete()
                backupPassword = ""
                busy = false
            }
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

    MaterialTheme(colorScheme = DarkScheme) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06101C), DarkBg, Color(0xFF02070D))))) {
            AnimatedBackdrop(true)
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    AppHeader(
                        route = route,
                        onBack = { route = when (route) { "security", "node" -> "settings"; "backup" -> "security"; else -> "home" } },
                        onActivity = { route = "activity" }
                    )
                },
                bottomBar = {
                    if (route in rootRoutes) NavigationBar(
                        containerColor = Color(0xF207101A),
                        contentColor = DarkText,
                        tonalElevation = 0.dp
                    ) {
                        listOf(
                            Triple("home", "Home", Icons.Default.Home),
                            Triple("wallets", "Wallet", Icons.Default.AccountBalanceWallet),
                            Triple("activity", "Activity", Icons.Default.ShowChart),
                            Triple("settings", "Settings", Icons.Default.Settings)
                        ).forEach { (key, label, icon) ->
                            NavigationBarItem(
                                selected = route == key,
                                onClick = { route = key },
                                icon = { Icon(icon, null) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Electric,
                                    selectedTextColor = Electric,
                                    indicatorColor = Color(0xFF102B43),
                                    unselectedIconColor = DarkMuted,
                                    unselectedTextColor = DarkMuted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (route) {
                        "home" -> WalletHome(
                            node = node,
                            state = state,
                            balance = balance,
                            transactions = transactions,
                            busy = busy,
                            onSend = { route = "send" },
                            onReceive = {
                                scope.launch {
                                    busy = true
                                    runCatching { receiveAddress = node.newAddress(); route = "receive" }
                                        .onFailure { Toast.makeText(context, "Unable to create a receiving address: ${it.message}", Toast.LENGTH_LONG).show() }
                                    busy = false
                                }
                            },
                            onRefresh = {
                                scope.launch {
                                    busy = true
                                    runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } }
                                        .onFailure { Toast.makeText(context, "Refresh failed: ${it.message}", Toast.LENGTH_LONG).show() }
                                    busy = false
                                }
                            },
                            onActivity = { route = "activity" },
                            onNode = { route = "node" }
                        )
                        "wallets" -> WalletManagerPage(balance, walletInfo, onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }, onSecurity = { route = "security" })
                        "activity" -> ActivityPage(state, transactions)
                        "settings" -> SettingsPage(
                            state = state,
                            info = walletInfo,
                            onSecurity = { route = "security" },
                            onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            onLock = { scope.launch { runCatching { node.lockWallet() } } },
                            onNode = { route = "node" },
                            onWallets = { route = "wallets" }
                        )
                        "send" -> SendPage(
                            node = node,
                            info = walletInfo,
                            balance = balance,
                            busy = busy,
                            onBusy = { busy = it },
                            onComplete = {
                                route = "home"
                                scope.launch { runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } } }
                            }
                        )
                        "receive" -> ReceivePage(receiveAddress, onCopy = { copy(context, receiveAddress) })
                        "node" -> NodeStatusPage(state, onRetry = { node.start() })
                        "security" -> SecurityPage(info = walletInfo, onBackup = { route = "backup" }, onChangePasskey = { dialog = DialogKind.Security }, onLock = { scope.launch { runCatching { node.lockWallet() } } })
                        "backup" -> BackupPage(
                            password = backupPassword,
                            onPasswordChange = { backupPassword = it },
                            onExport = { backupExportLauncher.launch("stohn-wallet-backup.stohnbackup") },
                            onRestore = { backupImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                        )
                    }
                }
            }
            AnimatedVisibility(visible = busy, modifier = Modifier.align(Alignment.Center), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Surface(shape = RoundedCornerShape(24.dp), color = DarkSurface2, tonalElevation = 8.dp) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Electric)
                        Text("Working…", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        when (val d = dialog) {
            DialogKind.Security -> SecurityDialog(node, walletInfo, onDismiss = { dialog = null })
            null -> Unit
        }
    }
}

private enum class DialogKind { Security }

@Composable
private fun AnimatedBackdrop(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "nebula")
    val pulse by transition.animateFloat(0.82f, 1.14f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().alpha(if (dark) .22f else .10f).scale(pulse).background(Brush.radialGradient(listOf(Blue, Color.Transparent), radius = 720f)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(route: String, onBack: () -> Unit, onActivity: () -> Unit) {
    val root = route in setOf("home", "wallets", "activity", "settings")
    val title = when (route) {
        "home" -> "Stohn"
        "wallets" -> "Wallets"
        "activity" -> "Transactions"
        "settings" -> "Settings"
        "send" -> "Send STOHN"
        "receive" -> "Receive STOHN"
        "node" -> "Node Status"
        "security" -> "Security"
        "backup" -> "Backup Wallet"
        else -> "Stohn"
    }
    CenterAlignedTopAppBar(
        navigationIcon = {
            if (root) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                    Image(painterResource(R.drawable.stohn_logo), "Stohn", Modifier.size(34.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontSize = 21.sp)
                }
            } else IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        },
        title = { if (!root) Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        actions = {
            if (root) IconButton(onClick = onActivity) { Icon(Icons.Default.NotificationsNone, "Activity", tint = DarkText) }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, titleContentColor = DarkText, navigationIconContentColor = DarkText)
    )
}

@Composable
private fun WalletHome(
    node: NodeController,
    state: NodeController.State,
    balance: Double,
    transactions: List<WalletRpc.WalletTransaction>,
    busy: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onRefresh: () -> Unit,
    onActivity: () -> Unit,
    onNode: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
        item {
            Text("PEOPLE. POWER. DECENTRALIZED.", color = DarkMuted, fontSize = 9.sp, letterSpacing = 1.6.sp)
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101F2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24394E))
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total Balance", color = DarkMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Visibility, null, tint = DarkMuted, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("%.3f".format(balance), fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text("STOHN", color = Electric, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Available in your Stohn wallet", color = DarkMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        QuickAction("Send", Icons.Default.NorthEast, onSend)
                        QuickAction("Receive", Icons.Default.SouthWest, onReceive)
                        QuickAction("Scan", Icons.Default.QrCodeScanner, onReceive)
                        QuickAction("History", Icons.Default.Schedule, onActivity)
                    }
                }
            }
        }
        item {
            Card(
                onClick = onNode,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1824)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF203448))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Node Status", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${if (state.status == NodeController.Status.READY) "●" else "●"} ${state.status.name.lowercase().replaceFirstChar { it.uppercase() }}", color = if (state.status == NodeController.Status.ERROR) Danger else Success, fontSize = 12.sp)
                        Icon(Icons.Default.ChevronRight, null, tint = DarkMuted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column { Text("Block Height", fontSize = 10.sp, color = DarkMuted); Text("${state.blocks}", fontSize = 12.sp) }
                        Column { Text("Peers", fontSize = 10.sp, color = DarkMuted); Text("${state.peers}", fontSize = 12.sp) }
                        Column { Text("Connection", fontSize = 10.sp, color = DarkMuted); Text("Local Node", fontSize = 12.sp) }
                    }
                    if (state.status == NodeController.Status.ERROR) {
                        OutlinedButton(onClick = { node.start() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Retry node startup") }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Transactions", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onActivity) { Text("View All", color = Electric) }
            }
        }
        if (transactions.isEmpty()) item { EmptyCard("Your recent transactions will appear here") }
        items(transactions.take(3), key = { it.txid + it.time }) { tx -> TransactionRow(tx) }
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 54.dp).clickable(onClick = onClick)) {
        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF0D2A43), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1A5B91))) {
            Icon(icon, label, Modifier.padding(12.dp).size(22.dp), tint = Electric)
        }
        Text(label, fontSize = 10.sp, color = DarkText, modifier = Modifier.padding(top = 5.dp))
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
private fun TransactionRow(tx: WalletRpc.WalletTransaction) {
    val received = tx.amount >= 0
    val timestamp = remember(tx.time) {
        runCatching { java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(tx.time * 1000)) }.getOrDefault("")
    }
    Card(
        Modifier.fillMaxWidth(),
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
                Text("%+.3f STOHN".format(tx.amount), color = if (received) Success else Danger, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("${tx.confirmations} confirmations", color = DarkMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun WalletManagerPage(balance: Double, info: WalletRpc.WalletInfoSnapshot?, onImport: () -> Unit, onSecurity: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Your wallet", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Manage this device’s Stohn wallet", color = DarkMuted, fontSize = 12.sp) }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E2030)), border = androidx.compose.foundation.BorderStroke(1.dp, Blue)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF0B3154)) { Icon(Icons.Default.AccountBalanceWallet, null, Modifier.padding(13.dp), tint = Electric) }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) { Text("Main Wallet", fontWeight = FontWeight.Bold); Text("%.3f STOHN".format(balance), color = DarkMuted, fontSize = 12.sp) }
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
private fun ActivityPage(state: NodeController.State, txs: List<WalletRpc.WalletTransaction>) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val visible = txs.filter { filter == "All" || (filter == "Received" && it.amount >= 0) || (filter == "Sent" && it.amount < 0) }
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
        items(visible, key = { it.txid + it.time }) { tx -> TransactionRow(tx) }
    }
}

@Composable
private fun NodeStatusPage(state: NodeController.State, onRetry: () -> Unit) {
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
private fun NodeFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, color = DarkMuted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SettingsPage(
    state: NodeController.State,
    info: WalletRpc.WalletInfoSnapshot?,
    onSecurity: () -> Unit,
    onImport: () -> Unit,
    onLock: () -> Unit,
    onNode: () -> Unit,
    onWallets: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Wallet and node preferences", color = DarkMuted, fontSize = 12.sp) }
        item { Text("PREFERENCES", color = Electric, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 8.dp)) }
        item { SettingCard("Security", "PIN, passkey and wallet protection", Icons.Default.Lock, onSecurity) }
        item { SettingCard("Node Status", "${state.status} · ${state.peers} peers", Icons.Default.Dns, onNode) }
        item { SettingCard("Wallet", if (info?.encrypted == true) "Encrypted wallet · Manage keys" else "Manage wallet and keys", Icons.Default.AccountBalanceWallet, onWallets) }
        item { SettingCard("Import wallet.dat", "Migrate with official Core tooling", Icons.Default.FileOpen, onImport) }
        item { SettingCard("Lock Wallet", "Remove the decryption key from memory", Icons.Default.Lock, onLock) }
        item { SettingCard("About Stohn", "Full node · Local RPC", Icons.Default.Info, {}) }
    }
}

@Composable
private fun SecurityPage(info: WalletRpc.WalletInfoSnapshot?, onBackup: () -> Unit, onChangePasskey: () -> Unit, onLock: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Security", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Protect access to your wallet", color = DarkMuted, fontSize = 12.sp) }
        item { SettingCard("Wallet passkey", if (info?.encrypted == true) "Encrypted · Change passkey" else "Not encrypted · Add passkey", Icons.Default.Lock, onChangePasskey) }
        item { SettingCard("Backup Wallet", "Create or restore an encrypted backup", Icons.Default.Backup, onBackup) }
        item { SettingCard("Lock wallet", "Lock spending keys now", Icons.Default.Lock, onLock) }
        item { Text("Your wallet passkey is handled by Stohn Core on this device. Keep a separate, tested backup of your wallet file.", color = DarkMuted, fontSize = 11.sp, modifier = Modifier.padding(4.dp)) }
    }
}

@Composable
private fun BackupPage(password: String, onPasswordChange: (String) -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
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
private fun SendPage(
    node: NodeController,
    info: WalletRpc.WalletInfoSnapshot?,
    balance: Double,
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
                trailingIcon = { Icon(Icons.Default.QrCodeScanner, "Scan QR", tint = Electric) }
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
                trailingIcon = { TextButton(onClick = { amountText = "%.8f".format(balance).trimEnd('0').trimEnd('.') }) { Text("Max", color = Electric) } }
            )
        }
        item { Text("Available: %.8f STOHN".format(balance), color = DarkMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)) }
        item {
            Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1722)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2F3E))) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = Electric)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("Network Fee", fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text("Automatic · estimated by your node", color = DarkMuted, fontSize = 10.sp) }
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
                    val amount = amountText.toDoubleOrNull()
                    if (amount == null || amount <= 0.0 || address.isBlank()) return@OutlinedButton
                    scope.launch {
                        onBusy(true)
                        runCatching { estimate = node.estimateFee(address, amount, subtractFee) }
                            .onFailure { Toast.makeText(context, "Fee estimate failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        onBusy(false)
                    }
                },
                enabled = !busy && address.isNotBlank() && (amountText.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (estimate == null) "Estimate Fee" else "Update Fee Estimate") }
        }
        estimate?.let { fee -> item { FeeSummary(fee, subtractFee) } }
        item {
            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@Button
                    scope.launch {
                        onBusy(true)
                        try {
                            node.send(address, amount, if (info?.encrypted == true) passphrase.toCharArray() else null, subtractFee)
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
                },
                enabled = !busy && estimate != null && address.isNotBlank() && (amountText.toDoubleOrNull() ?: 0.0) > 0 && (info?.encrypted != true || passphrase.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Review & Send", fontWeight = FontWeight.Bold) }
        }
        item { Text("Only send STOHN to a STOHN address. Transactions cannot be reversed.", color = DarkMuted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ReceivePage(address: String, onCopy: () -> Unit) {
    val bitmap = remember(address) { qr(address, 720) }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Receive STOHN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Share this address to receive funds", color = DarkMuted, fontSize = 12.sp)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1824)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF203548))) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), "Stohn receive QR code", Modifier.size(238.dp)) }
                Text(address, fontSize = 12.sp, color = DarkText, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Copy Address")
                }
            }
        }
        Text("Use only this address for STOHN. Check the address before sharing.", color = DarkMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1722)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2F3E))) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = Electric)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(subtitle, color = DarkMuted, fontSize = 10.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = DarkMuted, modifier = Modifier.size(19.dp))
        }
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
private const val MAX_BACKUP_BYTES = 600L * 1024 * 1024
