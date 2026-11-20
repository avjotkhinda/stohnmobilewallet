package org.stohncoin.wallet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.core.NodeStorage
import org.stohncoin.wallet.core.SeedConnectionSelfTest
import org.stohncoin.wallet.runtime.RuntimeBackend
import org.stohncoin.wallet.runtime.TestnetPeer
import org.stohncoin.wallet.wallet.WalletRpc

/**
 * Where Node Status points the node: the chain, the storage mode and the custom peers. The peer
 * that applies is derived, so no caller can pass a testnet peer and a mainnet network together.
 */
data class NodeStatusTarget(
    val network: RuntimeBackend.Network = RuntimeBackend.Network.MAINNET,
    val pruneMode: NodeStorage.Mode,
    val testnetPeer: String? = null,
    val mainnetPeer: String? = null,
    /**
     * The peer the current Core run started without, as NodeController recorded it when Core was
     * launched. Null before a run has dropped one; this screen never re-derives it from the config.
     */
    val skippedPeer: String? = null
) {
    /** The peer Core would dial on [network]; the other network's saved value never applies. */
    val activePeer: String? get() = if (network == RuntimeBackend.Network.TESTNET) testnetPeer else mainnetPeer
}

/** What the chain has taken so far; either value is null while Core still measures it. */
data class NodeStatusStorage(val usage: NodeStorage.Usage? = null, val freeBytes: Long? = null)

/**
 * How Node Status drives the node: start and stop, the mode pickers, the custom peer and
 * diagnostics. Grouped so the screen's argument list stays short as it grows.
 */
class NodeStatusActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onPruneMode: (NodeStorage.Mode) -> Unit = {},
    val onNetwork: (RuntimeBackend.Network) -> Unit = {},
    val onTestnetPeer: (String?) -> Unit = {},
    val onMainnetPeer: (String?) -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val loadPeers: suspend () -> List<WalletRpc.PeerInfo> = { emptyList() }
)


/**
 * Which dialog Node Status has open, and what the custom-peer dialog starts from. Hoisted out of the
 * composable so a test can prove which button opens what, without mounting an [AlertDialog] window
 * and reading inside it.
 *
 * One field rather than a flag per dialog: at most one can be open, and the screen's three open/close
 * decisions are then asserted the same way.
 */
class NodeStatusDialogs {
    enum class Open { STORAGE, NETWORK, PEER }

    var open by mutableStateOf<Open?>(null)
        private set

    /** The text the peer dialog's field starts with; null means nothing is saved to start from. */
    var peerSeed by mutableStateOf<String?>(null)
        private set

    fun openStorage() {
        open = Open.STORAGE
    }

    fun openNetwork() {
        open = Open.NETWORK
    }

    fun openPeer(seed: String?) {
        peerSeed = seed
        open = Open.PEER
    }

    fun close() {
        open = null
    }
}


@Composable
internal fun rememberNodeStatusDialogs(): NodeStatusDialogs = remember { NodeStatusDialogs() }


/** Decides when the node screen should suggest a custom peer. */
internal object ZeroPeerHint {
    const val DELAY_MILLIS = 30_000L

    fun shouldShow(running: Boolean, peers: Int, runningForMillis: Long): Boolean =
        running && peers == 0 && runningForMillis >= DELAY_MILLIS

    /** Time left before the hint may appear, given how long Core has already been up. */
    fun remainingDelay(runningForMillis: Long?): Long =
        (DELAY_MILLIS - (runningForMillis ?: 0L)).coerceAtLeast(0L)
}


@Composable
fun NodeStatusPage(
    state: NodeController.State,
    target: NodeStatusTarget,
    storage: NodeStatusStorage,
    actions: NodeStatusActions,
    runningForMillis: Long? = null,
    dialogs: NodeStatusDialogs = rememberNodeStatusDialogs()
) {
    // Destructured so the body still reads as plain names: the surface stays four arguments wide.
    val (network, pruneMode) = target
    val (chainUsage, freeBytes) = storage
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Give Core a chance to connect before calling out that it has no peers.
    var showNoPeerHint by remember { mutableStateOf(false) }
    val activeCustomPeer = target.activePeer
    // The result belongs to one network/peer pair, so a new choice clears the old answer.
    var testingConnection by remember { mutableStateOf(false) }
    var connectionTest by remember(network, activeCustomPeer) { mutableStateOf<String?>(null) }
    val running = state.isRunning
    // The controller's record of the run that is (or was last) up: the stored config is not a
    // verdict on its own, because it can change under a run that already started without it.
    // Only a running Core is being started without it now, so the mark and the warning below wait
    // for a run to be up; a stopped node says nothing about a peer it is not skipping yet.
    val ignoredPeer = if (running) target.skippedPeer else null
    val peers = state.peers
    // Bumped whenever the node stops/starts or the peer count changes: a connection-test result
    // only describes the zero-peer episode it was run in, so it is dropped when that episode ends.
    var zeroPeerEpisode by remember { mutableIntStateOf(0) }
    LaunchedEffect(running, peers) {
        zeroPeerEpisode++
        connectionTest = null
        testingConnection = false
    }
    LaunchedEffect(running, peers, runningForMillis) {
        showNoPeerHint = false
        if (running && peers == 0) {
            val elapsed = runningForMillis ?: 0L
            val remaining = ZeroPeerHint.remainingDelay(runningForMillis)
            if (remaining > 0L) delay(remaining)
            showNoPeerHint = ZeroPeerHint.shouldShow(true, 0, elapsed + remaining)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1B28)), border = BorderStroke(1.dp, Color(0xFF1E3549))) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Circle, null, tint = if (state.status == NodeController.Status.ERROR) Danger else Success, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(9.dp)); Text(nodeHeadline(state), fontWeight = FontWeight.Bold)
                }
                Text(state.message, color = DarkMuted, fontSize = 12.sp)
                state.syncPercent?.let { percent -> SyncMeter(percent, state.blocks, state.headers) }
                HorizontalDivider(color = Color(0xFF203244))
                NodeFact("Block Height", state.blocks.toString())
                NodeFact("Headers", state.headers.toString())
                NodeFact("Connections", "${state.peers} peers")
                NodeFact("Network", network.label)
                TextButton(onClick = { dialogs.openNetwork() }) { Text("Change network", color = Electric) }
                NodeFact(
                    "Custom peer",
                    when {
                        activeCustomPeer.isNullOrBlank() -> "None set"
                        ignoredPeer != null -> "$activeCustomPeer (ignored)"
                        else -> activeCustomPeer
                    }
                )
                // The one control for setting or replacing the peer; the label says which. A second
                // button in the warning below would open this same dialog and split the user's choice.
                TextButton(onClick = { dialogs.openPeer(activeCustomPeer) }) {
                    Text(if (ignoredPeer != null) "Fix custom peer" else "Set custom peer", color = Electric)
                }
                ignoredPeer?.let { ignored ->
                    // Starting without it is silent otherwise: 0 peers would look like a bug.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Saved peer $ignored is not a valid address, so it is ignored and Stohn Core starts without it. " +
                                "Use Test reachability in the peer dialog to check a replacement address before saving it.",
                            color = Color(0xFFE9B455),
                            fontSize = 11.sp
                        )
                        // Replacing happens on the row above; this is the other way out of the warning.
                        TextButton(
                            onClick = {
                                // Clearing restarts a running node, so Core starts without the unusable value.
                                if (target.network == RuntimeBackend.Network.TESTNET) {
                                    actions.onTestnetPeer(null)
                                } else {
                                    actions.onMainnetPeer(null)
                                }
                            }
                        ) { Text("Clear saved peer", color = Electric, fontSize = 11.sp) }
                    }
                }
                if (showNoPeerHint && peers == 0) {
                    // A peer Core skipped was never dialled, so it cannot be blamed for 0 peers.
                    val zeroPeerHint = when {
                        ignoredPeer != null ->
                            "Still at 0 peers. Stohn's bundled seeds may be unreachable; the saved peer above is not in use."
                        !activeCustomPeer.isNullOrBlank() ->
                            "Still at 0 peers. The custom peer may be unreachable; check the address or try another peer."
                        else ->
                            "Still at 0 peers. Stohn's bundled seeds may be unreachable; set a Custom peer above."
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            zeroPeerHint,
                            color = Color(0xFFE9B455),
                            fontSize = 11.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    if (testingConnection) return@TextButton
                                    testingConnection = true
                                    connectionTest = null
                                    // Results are dropped if the episode ends while the probe is in flight.
                                    val episode = zeroPeerEpisode
                                    scope.launch {
                                        try {
                                            val result = SeedConnectionSelfTest.probe(network, activeCustomPeer)
                                            if (episode == zeroPeerEpisode) connectionTest = SeedConnectionSelfTest.summary(result)
                                            NodeController.appendDiagnostic(context, "SeedSelfTest", SeedConnectionSelfTest.report(result))
                                        } catch (t: Throwable) {
                                            if (episode == zeroPeerEpisode) {
                                                connectionTest = "Connection test failed: ${t.message ?: "unknown error"}"
                                            }
                                            NodeController.appendDiagnostic(context, "SeedSelfTest", "failed", t)
                                        } finally {
                                            if (episode == zeroPeerEpisode) testingConnection = false
                                        }
                                    }
                                },
                                enabled = !testingConnection
                            ) { Text(if (testingConnection) "Testing connection…" else "Test connection", color = Electric, fontSize = 11.sp) }
                            TextButton(onClick = actions.onDiagnostics) { Text("Full diagnostics", color = Electric, fontSize = 11.sp) }
                        }
                        connectionTest?.let { summary -> Text(summary, color = DarkMuted, fontSize = 11.sp) }
                    }
                }
                NodeFact("Node", "Local Full Node")
                NodeFact("Sync", nodeSyncLabel(state))
                HorizontalDivider(color = Color(0xFF203244))
                NodeFact("Chain data", chainUsage?.let { NodeStorage.format(it.bytes) } ?: "Measuring…")
                NodeFact("Free space", freeBytes?.let { NodeStorage.format(it) } ?: "Unknown")
                NodeFact("Storage mode", pruneMode.label)
                TextButton(onClick = { dialogs.openStorage() }) { Text("Change storage mode", color = Electric) }
            }
        }
        ConnectedPeersSection(running = running, peerCount = peers, loadPeers = actions.loadPeers)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = actions.onStart, enabled = state.canStart, modifier = Modifier.weight(1f)) { Text("Start node") }
            OutlinedButton(onClick = actions.onStop, enabled = state.isRunning, modifier = Modifier.weight(1f)) { Text("Stop node") }
        }
        Text(
            "Stopping the node takes the local RPC offline. The wallet, its keys and the downloaded chain stay on this device.",
            color = DarkMuted,
            fontSize = 11.sp
        )
    }
    when (dialogs.open) {
        NodeStatusDialogs.Open.STORAGE -> StorageModeDialog(
            current = pruneMode,
            onDismiss = { dialogs.close() },
            onSelect = { mode ->
                dialogs.close()
                if (mode != pruneMode) actions.onPruneMode(mode)
            }
        )
        NodeStatusDialogs.Open.NETWORK -> NetworkModeDialog(
            current = network,
            onDismiss = { dialogs.close() },
            onSelect = { mode ->
                dialogs.close()
                if (mode != network) actions.onNetwork(mode)
            }
        )
        NodeStatusDialogs.Open.PEER -> CustomPeerDialog(
            network = network,
            // The seed, not target.activePeer: a test can then prove which value the dialog got.
            current = dialogs.peerSeed,
            onDismiss = { dialogs.close() },
            onSave = { peer ->
                dialogs.close()
                if (target.network == RuntimeBackend.Network.TESTNET) actions.onTestnetPeer(peer) else actions.onMainnetPeer(peer)
            }
        )
        null -> Unit
    }
}


/**
 * The custom-peer dialog's mutable state, hoisted so the dialog's body and Save action can be
 * rendered and driven without an AlertDialog window.
 */
internal class CustomPeerDialogState(current: String?) {
    var peer by mutableStateOf(current.orEmpty())
    var error by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
    var checkResult by mutableStateOf<String?>(null)
}

/** Validates what was typed and saves it, or reports why it cannot be saved. */
internal fun CustomPeerDialogState.save(network: RuntimeBackend.Network, onSave: (String?) -> Unit) {
    val address = try {
        TestnetPeer.address(peer, SeedConnectionSelfTest.defaultPort(network))
    } catch (e: IllegalArgumentException) {
        error = e.message ?: "Invalid peer"
        return
    }
    onSave(address?.normalized)
}

/**
 * Everything inside the custom-peer dialog: the explanation, the field, the effective address and
 * the reachability check. Kept out of [AlertDialog] so the validation paths can be proven by
 * rendering it directly, without depending on a dialog window being discoverable.
 */
@Composable
internal fun CustomPeerDialogBody(network: RuntimeBackend.Network, state: CustomPeerDialogState) {
    val defaultPort = SeedConnectionSelfTest.defaultPort(network)
    val scope = rememberCoroutineScope()
    // Validate while typing so the effective address is always the one Core would dial.
    val validated = runCatching { TestnetPeer.address(state.peer, defaultPort) }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (network == RuntimeBackend.Network.TESTNET) {
                "Stohn Core's testnet has no DNS or fixed seeds, so it stays at 0 peers unless you supply one. Enter a reachable testnet node as host or host:port. Leave blank to clear it."
            } else {
                "Stohn mainnet has no DNS seeds and its bundled fixed seeds may be stale. If the node stays at 0 peers, enter a reachable mainnet node as host or host:port. Leave blank to clear it."
            },
            color = DarkMuted,
            fontSize = 11.sp
        )
        OutlinedTextField(
            value = state.peer,
            onValueChange = {
                state.peer = it
                state.error = null
                state.checkResult = null
            },
            singleLine = true,
            label = { Text("host or host:port") },
            keyboardOptions = KeyboardOptions(
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None
            )
        )
        Text(
            when {
                state.peer.isBlank() -> "Effective address: none. Saving an empty field clears this peer."
                validated != null && validated.explicitPort -> "Effective address: ${validated.formatted}"
                validated != null -> "Effective address: ${validated.formatted} (default ${network.label} port)"
                else -> "Effective address: enter a valid host or host:port."
            },
            color = DarkMuted,
            fontSize = 11.sp
        )
        state.error?.let { Text(it, color = Danger, fontSize = 11.sp) }
        TextButton(
            onClick = {
                if (state.checking) return@TextButton
                val address = try {
                    TestnetPeer.address(state.peer, defaultPort)
                } catch (e: IllegalArgumentException) {
                    state.error = e.message ?: "Invalid peer"
                    return@TextButton
                }
                if (address == null) {
                    state.error = "Enter a host to test"
                    return@TextButton
                }
                state.error = null
                state.checkResult = null
                state.checking = true
                scope.launch {
                    try {
                        val result = SeedConnectionSelfTest.probePeer(address.bareHost, address.port)
                        state.checkResult = SeedConnectionSelfTest.reachability(result)
                    } catch (t: Throwable) {
                        state.checkResult = "Reachability check failed: ${t.message ?: "unknown error"}"
                    } finally {
                        state.checking = false
                    }
                }
            },
            enabled = !state.checking && state.peer.isNotBlank()
        ) { Text(if (state.checking) "Testing…" else "Test reachability", color = Electric, fontSize = 11.sp) }
        state.checkResult?.let { Text(it, color = DarkMuted, fontSize = 11.sp) }
        Text(
            "A successful TCP check only means the address answered; it does not confirm a compatible Stohn node.",
            color = DarkMuted,
            fontSize = 10.sp
        )
    }
}

/** The dialog's Save action, separate from [AlertDialog] for the same reason as the body. */
@Composable
internal fun CustomPeerDialogSaveButton(network: RuntimeBackend.Network, state: CustomPeerDialogState, onSave: (String?) -> Unit) {
    TextButton(onClick = { state.save(network, onSave) }) { Text("Save") }
}

/** Adds a reachable peer when Core's bundled seeds do not connect. */
@Composable
internal fun CustomPeerDialog(network: RuntimeBackend.Network, current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val state = remember(current) { CustomPeerDialogState(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${network.label} peer") },
        text = { CustomPeerDialogBody(network, state) },
        confirmButton = { CustomPeerDialogSaveButton(network, state, onSave) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/** Lists the peers Core reports, so a stalled sync can be traced to a specific connection. */
@Composable
private fun ConnectedPeersSection(running: Boolean, peerCount: Int, loadPeers: suspend () -> List<WalletRpc.PeerInfo>) {
    var peers by remember { mutableStateOf<List<WalletRpc.PeerInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // Reloads when a peer connects or drops, and on Refresh; no timer, so an idle screen stays quiet.
    LaunchedEffect(running, peerCount, reloads) {
        if (!running) {
            peers = emptyList()
            error = null
            return@LaunchedEffect
        }
        refreshing = true
        try {
            peers = loadPeers()
            error = null
        } catch (t: Throwable) {
            // Keep the last good list on screen; only a first failure replaces it with an error.
            if (peers == null) error = t.message ?: "Stohn Core did not return its peers"
        } finally {
            refreshing = false
        }
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1B28)),
        border = BorderStroke(1.dp, Color(0xFF1E3549))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connected peers", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { reloads++ }, enabled = running && !refreshing) {
                    Text(if (refreshing) "Refreshing…" else "Refresh", color = Electric, fontSize = 11.sp)
                }
            }
            when {
                !running -> Text("Start the node to see its peers.", color = DarkMuted, fontSize = 11.sp)
                error != null -> Text(error.orEmpty(), color = Danger, fontSize = 11.sp)
                peers == null -> Text("Reading peers…", color = DarkMuted, fontSize = 11.sp)
                peers?.isEmpty() == true -> Text(
                    "No peers connected yet. Stohn Core is still looking for peers; if this lasts, set a custom peer or use the connection test on this screen.",
                    color = Color(0xFFE9B455),
                    fontSize = 11.sp
                )
                else -> {
                    peers.orEmpty().forEach { peer -> PeerRow(peer) }
                    Text("Addresses, direction and sync height as Stohn Core reports them.", color = DarkMuted, fontSize = 10.sp)
                }
            }
        }
    }
}


/** One connection: who it is, which way it opened, its latency and how far it has synced. */
@Composable
private fun PeerRow(peer: WalletRpc.PeerInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0A1722),
        border = BorderStroke(1.dp, Color(0xFF1D2F3E))
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    peer.address,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(shape = RoundedCornerShape(6.dp), color = if (peer.inbound) Color(0xFF2E2410) else Color(0xFF0D3833)) {
                    Text(
                        if (peer.inbound) "IN" else "OUT",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (peer.inbound) Color(0xFFE9B455) else Success,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            val details = buildList {
                add(peer.pingMillis?.let { "$it ms" } ?: "ping unknown")
                peer.syncedHeaders?.let { add("headers $it") }
                peer.startingHeight?.let { add("started at $it") }
            }
            Text(details.joinToString(" · "), color = DarkMuted, fontSize = 10.sp)
        }
    }
}


/** Lets the user pick which chain the node runs. */
@Composable
private fun NetworkModeDialog(current: RuntimeBackend.Network, onDismiss: () -> Unit, onSelect: (RuntimeBackend.Network) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Switching networks stops the node and uses that chain's own data. Wallets are network-specific.", color = DarkMuted, fontSize = 11.sp)
                RuntimeBackend.Network.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == mode, onClick = { onSelect(mode) })
                        Text(mode.label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/** Explains the one thing a prune change costs before it happens: the downloaded chain. */
@Composable
private fun StorageModeDialog(current: NodeStorage.Mode, onDismiss: () -> Unit, onSelect: (NodeStorage.Mode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chain storage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "A pruned node keeps only recent blocks, so it needs far less space, but it cannot serve old blocks to other wallets.",
                    color = DarkMuted,
                    fontSize = 11.sp
                )
                Text(
                    "Stohn Core only accepts a different mode on a fresh chain database, so switching deletes the downloaded chain and the node re-syncs from the beginning. Your wallet, keys and backups are not touched.",
                    color = Color(0xFFE9B455),
                    fontSize = 11.sp
                )
                NodeStorage.Mode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == mode, onClick = { onSelect(mode) })
                        Column {
                            Text(mode.label, fontSize = 14.sp)
                            Text(mode.detail, color = DarkMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


private fun nodeHeadline(state: NodeController.State): String = when (state.status) {
    NodeController.Status.READY -> "Node Synced"
    NodeController.Status.STOPPED -> "Node Stopped"
    else -> state.status.name.lowercase().replaceFirstChar { it.uppercase() }
}


private fun nodeSyncLabel(state: NodeController.State): String = when {
    state.canStart -> "Not running"
    state.status == NodeController.Status.STOPPING -> "Stopping"
    state.syncPercent == null -> "Waiting for headers"
    state.initialBlockDownload -> "In progress"
    else -> "Up to date"
}


/** Progress bar plus the counters behind it, so "63%" is never a mystery number. */
@Composable
private fun SyncMeter(percent: Int, blocks: Long, headers: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Electric,
            trackColor = Color(0xFF16293A)
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (percent >= 100) "Synchronized" else "${(headers - blocks).coerceAtLeast(0L)} blocks behind",
                color = DarkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            Text("$percent%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (percent >= 100) Success else Electric)
        }
    }
}


