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

val DarkBg = Color(0xFF030A18)


val DarkSurface = Color(0xFF091522)


val DarkSurface2 = Color(0xFF102235)


val DarkText = Color(0xFFF4F8FF)


val DarkMuted = Color(0xFFB7C8DF)


val Blue = Color(0xFF1687FF)


val Electric = Color(0xFF62C8FF)


val Success = Color(0xFF31D49A)


val Danger = Color(0xFFFF665F)


val DarkScheme = darkColorScheme(
    primary = Blue,
    secondary = Electric,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkMuted
)


@Composable
fun AnimatedBackdrop(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "nebula")
    val pulse by transition.animateFloat(0.82f, 1.14f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().alpha(if (dark) .22f else .10f).scale(pulse).background(Brush.radialGradient(listOf(Blue, Color.Transparent), radius = 720f)))
}


@Composable
fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 54.dp).clickable(onClick = onClick)) {
        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF0D2A43), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1A5B91))) {
            Icon(icon, label, Modifier.padding(12.dp).size(22.dp), tint = Electric)
        }
        Text(label, fontSize = 10.sp, color = DarkText, modifier = Modifier.padding(top = 5.dp))
    }
}


@Composable
fun FuturisticButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
        Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 12.sp, maxLines = 1)
    }
}


@Composable
fun StatusCard(title: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) { Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
    }
}


@Composable
fun SettingCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
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
fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }


fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Stohn address", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}


fun qr(text: String, size: Int): android.graphics.Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}.getOrNull()


/** Writes the QR into the cache directory exposed by FileProvider so it can be shared as an image. */
fun shareableQrImage(context: Context, bitmap: android.graphics.Bitmap): File? = runCatching {
    val dir = File(context.cacheDir, "qr-share").apply { mkdirs() }
    val file = File(dir, "stohn-address-${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    file
}.getOrNull()


const val MAX_IMPORT_BYTES = 256L * 1024 * 1024


const val MAX_BACKUP_BYTES = 600L * 1024 * 1024
