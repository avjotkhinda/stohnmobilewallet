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
import androidx.compose.ui.draw.clip
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
import org.stohncoin.wallet.core.NodeStorage
import org.stohncoin.wallet.wallet.Amounts
import org.stohncoin.wallet.wallet.BackupReminder
import org.stohncoin.wallet.wallet.BackupVerification
import org.stohncoin.wallet.wallet.WalletRpc
import org.stohncoin.wallet.security.AppAuthManager
import java.io.File

/** A backup file that has been checked against its manifest and is waiting for confirmation. */
private data class PendingRestore(val file: File, val verification: BackupVerification)

@Composable
fun StohnWalletScreen(node: NodeController) {
    val context = LocalContext.current
    val state by node.state.collectAsState()
    val backupReminder by node.backupReminder.collectAsState()
    var route by rememberSaveable { mutableStateOf("home") }
    var balance by remember { mutableLongStateOf(0L) }
    var transactions by remember { mutableStateOf(emptyList<WalletRpc.WalletTransaction>()) }
    var walletInfo by remember { mutableStateOf<WalletRpc.WalletInfoSnapshot?>(null) }
    var receiveAddress by remember { mutableStateOf("") }
    var backupPassword by rememberSaveable { mutableStateOf("") }
    // Result of the last Test Backup, cleared whenever the password or the chosen file changes.
    var backupVerification by remember { mutableStateOf<BackupVerification?>(null) }
    var backupVerifyError by remember { mutableStateOf<String?>(null) }
    // A chosen backup that has been unpacked and checked but not yet installed; confirmed first.
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Chain storage shown on the node screen; measured when that screen opens and after a mode change.
    var chainUsage by remember { mutableStateOf<NodeStorage.Usage?>(null) }
    var freeSpace by remember { mutableStateOf<Long?>(null) }
    var pruneMode by remember { mutableStateOf(node.pruneMode()) }
    var selectedTx by remember { mutableStateOf<WalletRpc.WalletTransaction?>(null) }
    // Address book is opened from Settings or Send; back returns to whichever one opened it.
    var addressBookFrom by rememberSaveable { mutableStateOf("home") }
    // Backup is reachable from Settings and from the home reminder; back returns to whichever.
    var backupFrom by rememberSaveable { mutableStateOf("security") }
    // Address chosen in the address book, prefilled on the send screen.
    var pendingSendAddress by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val rootRoutes = setOf("home", "wallets", "activity", "settings")
    val goBack: () -> Unit = {
        route = when (route) {
            "security", "node" -> "settings"
            "backup" -> backupFrom
            "txdetail" -> "activity"
            "addressbook" -> addressBookFrom
            else -> "home"
        }
    }
    BackHandler(enabled = route !in rootRoutes) { goBack() }

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
                // Read the manifest before installing anything: the user sees which wallet this
                // file says it holds, and can still walk away.
                pendingRestore = PendingRestore(staged, node.verifyWalletBackup(staged, backupPassword.toCharArray()))
            } catch (t: Throwable) {
                staged.delete()
                Toast.makeText(context, "Backup could not be checked: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun installPendingRestore(pending: PendingRestore) {
        scope.launch {
            val destination = File(context.filesDir, "fullmode/stohn-data/restored-wallet")
            busy = true
            try {
                require(!destination.exists()) { "A restored wallet already exists" }
                node.restoreWalletBackup(pending.file, backupPassword.toCharArray(), destination)
                refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi }
                Toast.makeText(context, "Wallet restored successfully", Toast.LENGTH_LONG).show()
                route = "home"
            } catch (t: Throwable) {
                Toast.makeText(context, "Restore failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                pending.file.delete()
                pendingRestore = null
                backupPassword = ""
                busy = false
            }
        }
    }

    pendingRestore?.let { pending ->
        val verification = pending.verification
        AlertDialog(
            onDismissRequest = { pending.file.delete(); pendingRestore = null },
            title = { Text("Restore this wallet?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        verification.summary
                            ?: "This backup does not record wallet details.",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "${verification.walletBytes} byte wallet \u00b7 SHA-256 ${verification.walletSha256.take(16)}\u2026",
                        color = DarkMuted,
                        fontSize = 11.sp
                    )
                    verification.notes.forEach { note ->
                        Text(note, color = Color(0xFFE9B455), fontSize = 11.sp)
                    }
                    Text(
                        "Restoring installs this wallet on this device. Nothing is replaced until the import succeeds.",
                        color = DarkMuted,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { installPendingRestore(pending) }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pending.file.delete(); pendingRestore = null }) { Text("Cancel") }
            }
        )
    }

    val backupVerifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = File(context.cacheDir, "stohn-backup-verify-${System.currentTimeMillis()}.stohnbackup")
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
                // Decrypts and unpacks in cache only; the live wallet is never opened or replaced.
                backupVerification = node.verifyWalletBackup(staged, backupPassword.toCharArray())
                backupVerifyError = null
            } catch (t: Throwable) {
                backupVerification = null
                backupVerifyError = t.message ?: "The backup could not be verified"
            } finally {
                staged.delete()
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Auto-start on open, but a node the user stopped stays stopped across a rotation.
        node.startUnlessStopped()
    }
    LaunchedEffect(state.status) {
        if (state.status == NodeController.Status.READY || state.status == NodeController.Status.SYNCING) {
            runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } }
        }
    }
    // Keyed on the route and on reaching READY, so the data directory is not walked every poll tick.
    LaunchedEffect(route, state.status == NodeController.Status.READY) {
        if (route == "node") {
            pruneMode = node.pruneMode()
            runCatching { chainUsage = node.chainUsage() }
            freeSpace = node.freeBytes()
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
                        onBack = goBack,
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
                            reminder = backupReminder,
                            balance = balance,
                            transactions = transactions,
                            busy = busy,
                            onTransaction = { tx -> selectedTx = tx; route = "txdetail" },
                            onSend = { pendingSendAddress = ""; route = "send" },
                            onReceive = {
                                scope.launch {
                                    busy = true
                                    runCatching { receiveAddress = node.newAddress(); route = "receive" }
                                        .onFailure { Toast.makeText(context, "Unable to create a receiving address: ${it.message}", Toast.LENGTH_LONG).show() }
                                    busy = false
                                }
                            },
                            onActivity = { route = "activity" },
                            onNode = { route = "node" },
                            onBackup = { backupFrom = "home"; route = "backup" }
                        )
                        "wallets" -> WalletManagerPage(balance, walletInfo, onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }, onSecurity = { route = "security" })
                        "activity" -> ActivityPage(
                            state = state,
                            txs = transactions,
                            onTransaction = { tx -> selectedTx = tx; route = "txdetail" }
                        )
                        "txdetail" -> TransactionDetailPage(selectedTx)
                        "settings" -> SettingsPage(
                            state = state,
                            info = walletInfo,
                            onSecurity = { route = "security" },
                            onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            onLock = { scope.launch { runCatching { node.lockWallet() } } },
                            onNode = { route = "node" },
                            onWallets = { route = "wallets" },
                            onAddressBook = { addressBookFrom = "settings"; route = "addressbook" }
                        )
                        "addressbook" -> AddressBookPage(
                            onSend = { addr -> pendingSendAddress = addr; route = "send" }
                        )
                        "send" -> SendPage(
                            node = node,
                            info = walletInfo,
                            balance = balance,
                            busy = busy,
                            initialAddress = pendingSendAddress,
                            onAddressBook = { addressBookFrom = "send"; route = "addressbook" },
                            onBusy = { busy = it },
                            onComplete = {
                                route = "home"
                                scope.launch { runCatching { refresh(node) { b, tx, wi -> balance = b; transactions = tx; walletInfo = wi } } }
                            }
                        )
                        "receive" -> ReceivePage(receiveAddress, onCopy = { copy(context, receiveAddress) })
                        "node" -> NodeStatusPage(
                            state = state,
                            chainUsage = chainUsage,
                            freeBytes = freeSpace,
                            pruneMode = pruneMode,
                            onStart = { node.start() },
                            onStop = { node.stop() },
                            onPruneMode = { mode ->
                                scope.launch {
                                    busy = true
                                    runCatching { node.applyPruneMode(mode) }
                                        .onSuccess {
                                            pruneMode = node.pruneMode()
                                            runCatching { chainUsage = node.chainUsage() }
                                            freeSpace = node.freeBytes()
                                            Toast.makeText(context, "Storage mode set to ${mode.label}", Toast.LENGTH_LONG).show()
                                        }
                                        .onFailure { Toast.makeText(context, "Storage mode unchanged: ${it.message}", Toast.LENGTH_LONG).show() }
                                    busy = false
                                }
                            }
                        )
                        "security" -> SecurityPage(
                            info = walletInfo,
                            onBackup = { backupFrom = "security"; route = "backup" },
                            onChangePasskey = { dialog = DialogKind.Security },
                            onLock = { scope.launch { runCatching { node.lockWallet() } } }
                        )
                        "backup" -> BackupPage(
                            password = backupPassword,
                            verification = backupVerification,
                            verifyError = backupVerifyError,
                            lastBackupSummary = BackupReminder.summary(backupReminder, System.currentTimeMillis()),
                            onPasswordChange = {
                                backupPassword = it
                                backupVerification = null
                                backupVerifyError = null
                            },
                            onExport = { backupExportLauncher.launch("stohn-wallet-backup.stohnbackup") },
                            onRestore = { backupImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            onVerify = { backupVerifyLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
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

        when (dialog) {
            DialogKind.Security -> SecurityDialog(node, walletInfo, onDismiss = { dialog = null })
            null -> Unit
        }
    }
}


private enum class DialogKind { Security }


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
        "addressbook" -> "Address Book"
        "node" -> "Node Status"
        "security" -> "Security"
        "backup" -> "Backup Wallet"
        "txdetail" -> "Transaction"
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
    reminder: BackupReminder.State,
    balance: Long,
    transactions: List<WalletRpc.WalletTransaction>,
    busy: Boolean,
    onTransaction: (WalletRpc.WalletTransaction) -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onActivity: () -> Unit,
    onNode: () -> Unit,
    onBackup: () -> Unit
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
                    Text(Amounts.format(balance), fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
        if (BackupReminder.shouldRemind(reminder, System.currentTimeMillis())) {
            item {
                Card(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1B06)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7A5A18))
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, null, tint = Color(0xFFFFC24B), modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (reminder.hasBackup) "Your backup is out of date" else "No backup yet", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "${BackupReminder.summary(reminder, System.currentTimeMillis())}. Without a recent backup this wallet cannot be recovered.",
                                color = DarkMuted,
                                fontSize = 10.sp
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = DarkMuted, modifier = Modifier.size(19.dp))
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
                        Text(
                            "${if (state.status == NodeController.Status.READY) "●" else "●"} ${state.status.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            color = if (state.status == NodeController.Status.ERROR) Danger else Success,
                            fontSize = 12.sp
                        )
                        Icon(Icons.Default.ChevronRight, null, tint = DarkMuted)
                    }
                    state.syncPercent?.let { percent ->
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = Electric,
                            trackColor = Color(0xFF16293A)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column { Text("Block Height", fontSize = 10.sp, color = DarkMuted); Text("${state.blocks}", fontSize = 12.sp) }
                        Column { Text("Sync", fontSize = 10.sp, color = DarkMuted); Text(state.syncPercent?.let { "$it%" } ?: "—", fontSize = 12.sp) }
                        Column { Text("Peers", fontSize = 10.sp, color = DarkMuted); Text("${state.peers}", fontSize = 12.sp) }
                        Column { Text("Connection", fontSize = 10.sp, color = DarkMuted); Text("Local Node", fontSize = 12.sp) }
                    }
                    if (state.canStart) {
                        OutlinedButton(onClick = { node.start() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Start node") }
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
        items(transactions.take(3), key = { it.txid + it.time }) { tx -> TransactionRow(tx) { onTransaction(tx) } }
    }
}
