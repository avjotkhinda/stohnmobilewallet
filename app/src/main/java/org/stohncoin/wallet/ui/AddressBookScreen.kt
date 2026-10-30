package org.stohncoin.wallet.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.wallet.AddressBook
import org.stohncoin.wallet.wallet.Amounts
import java.io.File

/**
 * Saved addresses with their local labels.
 *
 * Only labels live here; balances and history stay with the node. Saving an address never
 * contacts the network.
 */
@Composable
fun AddressBookPage(onSend: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book = remember(context) { AddressBook.initialize(File(context.filesDir, ADDRESS_BOOK_DIR)) }
    var entries by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(reload) {
        entries = withContext(Dispatchers.IO) { book.labels().toList() }
            .sortedBy { (_, label) -> label.lowercase() }
    }

    val visible = entries.filter { (address, label) ->
        query.isBlank() || label.contains(query, ignoreCase = true) || address.contains(query, ignoreCase = true)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 22.dp)
    ) {
        item {
            Text("Address book", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Saved addresses never leave this device", color = DarkMuted, fontSize = 12.sp)
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search labels or addresses") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = DarkMuted) },
                singleLine = true,
                shape = RoundedCornerShape(15.dp)
            )
        }
        item {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add address")
            }
        }
        if (visible.isEmpty()) item {
            EmptyCard(if (entries.isEmpty()) "No saved addresses yet. Save one from the send screen or here." else "No address matches \"$query\"")
        }
        items(visible, key = { it.first }) { entry ->
            val (address, label) = entry
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1722)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2F3E))
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { copy(context, address) }) {
                            Icon(Icons.Default.ContentCopy, "Copy address", Modifier.size(17.dp), tint = DarkMuted)
                        }
                        IconButton(onClick = { onSend(address) }) {
                            Icon(Icons.Default.NorthEast, "Send to $label", Modifier.size(17.dp), tint = Electric)
                        }
                        IconButton(onClick = { editing = entry }) {
                            Icon(Icons.Default.Edit, "Rename $label", Modifier.size(17.dp), tint = DarkMuted)
                        }
                    }
                    Text(address, fontSize = 11.sp, color = DarkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 9.dp))
                }
            }
        }
    }

    if (creating || editing != null) {
        val original = editing
        var addressInput by remember(original, creating) { mutableStateOf(original?.first ?: "") }
        var labelInput by remember(original, creating) { mutableStateOf(original?.second ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (original == null) "Save an address" else "Edit label", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Stohn address or payment request") },
                        enabled = original == null,
                        maxLines = 3,
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { if (it.length <= 64) labelInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Label") },
                        placeholder = { Text("e.g. Rent") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Text("Paste an address or a stohn: request; the address is extracted for you.", color = DarkMuted, fontSize = 10.sp)
                    if (original != null) TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { book.clearLabel(original.first) }
                            editing = null
                            reload++
                        }
                    }) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp), tint = Danger)
                        Spacer(Modifier.width(6.dp))
                        Text("Remove from address book", color = Danger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = original?.first ?: Amounts.parseUri(addressInput)?.address
                    val label = labelInput.trim()
                    if (target.isNullOrBlank() || label.isEmpty()) {
                        Toast.makeText(context, "Enter a Stohn address and a label", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        withContext(Dispatchers.IO) { book.setLabel(target, label) }
                        creating = false
                        editing = null
                        reload++
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } }
        )
    }
}
