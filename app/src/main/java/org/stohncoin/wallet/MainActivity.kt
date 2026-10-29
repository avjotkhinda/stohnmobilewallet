package org.stohncoin.wallet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.security.AppAuthManager
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
        // Blocks screenshots, screen recording and the recents thumbnail for wallet content.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val node = NodeController.get(applicationContext)
        locked = AppAuthManager.isAppLockEnabled(this)
        setContent {
            if (locked) {
                LockScreen(onUnlocked = { locked = false })
            } else {
                StohnWalletScreen(node)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AppAuthManager.isAppLockEnabled(this)) {
            locked = false
            return
        }
        // Re-lock when the app was away longer than the grace period; short trips into a system
        // dialog (biometric prompt, scanner, file picker) do not throw the user back to the lock.
        val away = if (backgroundedAtMillis > 0L) System.currentTimeMillis() - backgroundedAtMillis else Long.MAX_VALUE
        if (away >= AppAuthManager.lockGraceMillis(this)) locked = true
    }

    override fun onStop() {
        backgroundedAtMillis = System.currentTimeMillis()
        super.onStop()
    }
}
