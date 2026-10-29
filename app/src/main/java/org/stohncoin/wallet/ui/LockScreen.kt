package org.stohncoin.wallet.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import org.stohncoin.wallet.security.AppAuthManager

/**
 * Gate shown on cold start and after the app returns from a background period longer than the
 * configured grace. PIN entry is throttled; biometric is only offered when a PIN backs it up.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var blockedMillis by remember { mutableStateOf(0L) }
    val hasPin = remember { AppAuthManager.hasPin(context) }
    val biometricEnabled = remember { AppAuthManager.isBiometricEnabled(context) }

    fun showBiometric() {
        if (!hasPin) return
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(context).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                blockedMillis = 0L
                error = null
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // Cancelling the system prompt falls back to the PIN field; never unlock on error.
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    error = errString.toString()
                }
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Stohn Wallet")
                .setSubtitle("Authenticate to access your wallet")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    LaunchedEffect(Unit) {
        if (biometricEnabled) showBiometric()
    }
    LaunchedEffect(blockedMillis) {
        while (blockedMillis > 0L) {
            delay(1000)
            blockedMillis = (blockedMillis - 1000L).coerceAtLeast(0L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02070D))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF4F9BFF), modifier = Modifier.height(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Stohn Wallet Locked", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Authenticate to unlock", fontSize = 14.sp, color = Color(0xFF9CA3AF))
        Spacer(modifier = Modifier.height(32.dp))

        if (hasPin) {
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= AppAuthManager.MAX_PIN_LENGTH) {
                        pin = it
                        error = null
                    }
                },
                label = { Text("Enter PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                isError = error != null,
                enabled = blockedMillis == 0L
            )
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF665F), fontSize = 12.sp)
            }
            if (blockedMillis > 0L) {
                Text(
                    "Try again in ${(blockedMillis / 1000L) + 1L}s",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    when (val verdict = AppAuthManager.submitPin(context, pin)) {
                        is AppAuthManager.PinVerdict.Success -> {
                            pin = ""
                            error = null
                            blockedMillis = 0L
                            onUnlocked()
                        }
                        is AppAuthManager.PinVerdict.Incorrect -> {
                            pin = ""
                            error = "Incorrect PIN"
                        }
                        is AppAuthManager.PinVerdict.Blocked -> {
                            pin = ""
                            blockedMillis = verdict.retryAfterMillis
                            error = "Too many attempts"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.length >= AppAuthManager.MIN_PIN_LENGTH && blockedMillis == 0L
            ) {
                Text("Unlock")
            }
        }

        if (biometricEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showBiometric() },
                modifier = Modifier.fillMaxWidth(),
                enabled = blockedMillis == 0L
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Use Biometric/Device Unlock")
            }
        }
    }
}
