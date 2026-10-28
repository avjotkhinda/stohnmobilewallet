package org.stohncoin.wallet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val node = NodeController.get(applicationContext)
        setContent {
            val needsAuth = remember { AppAuthManager.isAppLockEnabled(this@MainActivity) }
            var unlocked by remember { mutableStateOf(!needsAuth) }
            if (!unlocked && needsAuth) {
                LockScreen { unlocked = true }
            } else {
                StohnWalletScreen(node)
            }
        }
    }
}
