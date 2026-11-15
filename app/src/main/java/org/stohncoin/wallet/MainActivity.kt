package org.stohncoin.wallet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import org.stohncoin.wallet.ui.DarkScheme
import org.stohncoin.wallet.ui.DarkText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.runtime.RuntimeManifestTransport
import org.stohncoin.wallet.runtime.RuntimeTemp
import org.stohncoin.wallet.runtime.SignedRuntimeManifest
import org.stohncoin.wallet.security.AppAuthManager
import org.stohncoin.wallet.security.LockGate
import org.stohncoin.wallet.ui.LockScreen
import org.stohncoin.wallet.ui.StohnWalletScreen

class MainActivity : FragmentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* FGS remains functional even when notification permission is denied. */ }

    /** Owned by the activity so both cold start and resume gate on the authenticator. */
    private var locked by mutableStateOf(false)
    private var backgroundedAtMillis = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val node = NodeController.get(applicationContext)
        // A process that dies mid-run leaves PRoot's unpacked loader in the app cache. Collect it
        // at start, but only while no node is running so a live process keeps its own files.
        if (!node.state.value.isRunning) {
            Thread { runCatching { RuntimeTemp.purge(cacheDir) } }.start()
        }
        // The runtime update path. While RuntimeManifest.MANIFEST_URL is blank this is a no-op that
        // makes no request at all; a failed fetch only declines, leaving the pins in force untouched.
        Thread {
            runCatching {
                RuntimeManifestTransport.refresh(
                    SignedRuntimeManifest.updateDirectory(applicationContext)
                )
            }
        }.start()
        // Default to secure; StohnWalletScreen clears this on non-sensitive screens so users can
        // screenshot node status and addresses, while key/backup screens stay protected.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        locked = AppAuthManager.isAppLockEnabled(this)
        setContent {
            MaterialTheme(colorScheme = DarkScheme) {
                CompositionLocalProvider(LocalContentColor provides DarkText) {
                    if (locked) {
                    LockScreen(onUnlocked = { locked = false })
                } else {
                    StohnWalletScreen(node)
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The decision itself is pure (LockGate) so the cold-start/resume transitions are unit
        // tested: re-lock once the configured grace has passed, stay open for a short trip into a
        // system dialog, and never gate when no PIN is configured.
        locked = LockGate.mustRelockOnStart(
            appLockEnabled = AppAuthManager.isAppLockEnabled(this),
            backgroundedAtMillis = backgroundedAtMillis,
            now = System.currentTimeMillis(),
            graceMillis = AppAuthManager.lockGraceMillis(this)
        )
    }

    override fun onStop() {
        backgroundedAtMillis = System.currentTimeMillis()
        super.onStop()
    }

    internal companion object {
        @Volatile private var crashHandlerInstalled = false

        /** Installs the persistent handler once per process, never once per activity recreation. */
        internal fun installCrashHandler(context: Context) {
            if (crashHandlerInstalled) return
            synchronized(this) {
                if (crashHandlerInstalled) return
                val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    NodeController.appendDiagnostic(context, "Uncaught", "thread=$thread", throwable)
                    previousHandler?.uncaughtException(thread, throwable)
                }
                crashHandlerInstalled = true
            }
        }
    }
}
