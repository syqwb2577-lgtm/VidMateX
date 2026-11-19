package app.vidmatex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.model.ThemeMode
import app.vidmatex.navigation.VidMateXNavHost
import app.vidmatex.ui.theme.VidMateXTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Host for the notification-permission rationale snackbar. Messages are queued
     *  before the host attaches, so a result during early startup is not lost. */
    private val snackbarHostState = SnackbarHostState()

    /** POST_NOTIFICATIONS runtime permission (API 33+). Notifications are best-effort:
     *  if denied, downloads still run silently (the foreground service is unaffected). */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showNotificationRationale()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        setContent {
            val themeMode by settingsRepository.settings
                .map { it.themeMode }
                .collectAsState(initial = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            VidMateXTheme(darkTheme = darkTheme) {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        VidMateXNavHost()
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        // Previously denied (or the user checked "don't ask again"): explain why the
        // permission matters and offer a shortcut to system settings instead of asking
        // again immediately. The app remains fully usable without notifications.
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationRationale()
        } else {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showNotificationRationale() {
        lifecycleScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = getString(R.string.notification_permission_rationale),
                actionLabel = getString(R.string.notification_permission_settings),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
}

private fun isSystemInDarkTheme(): Boolean =
    android.content.res.Configuration.UI_MODE_NIGHT_MASK.let { mask ->
        (android.content.res.Resources.getSystem().configuration.uiMode and mask) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
