package dev.spelfrog.getsynced.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.spelfrog.getsynced.settings.SettingsRepository
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    companion object {
        /** Boolean extra: open the setup screen directly. */
        const val EXTRA_OPEN_SETUP = "openSetup"
        const val ROUTE_STATUS = "status"
        const val ROUTE_SETUP = "setup"
    }

    /** Set when an intent asks for the setup screen; consumed by the NavHost. */
    private var openSetupRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openSetupRequest = intent.wantsSetup()

        setContent {
            GetSyncedTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.wantsSetup()) openSetupRequest = true
    }

    private fun Intent?.wantsSetup(): Boolean = this?.getBooleanExtra(EXTRA_OPEN_SETUP, false) == true

    @Composable
    private fun AppNavHost() {
        val repo = remember { SettingsRepository.get(applicationContext) }
        // Read settings once to pick the start destination; render nothing until known.
        var start by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val configured = runCatching { repo.settings.first().isConfigured }.getOrDefault(false)
            start = if (configured) ROUTE_STATUS else ROUTE_SETUP
        }
        val startRoute = start ?: return

        val nav = rememberNavController()
        LaunchedEffect(openSetupRequest) {
            if (openSetupRequest) {
                openSetupRequest = false
                if (nav.currentDestination?.route != ROUTE_SETUP) {
                    nav.navigate(ROUTE_SETUP) { launchSingleTop = true }
                }
            }
        }

        NavHost(navController = nav, startDestination = startRoute) {
            composable(ROUTE_STATUS) {
                StatusScreen(onOpenSetup = { nav.navigate(ROUTE_SETUP) { launchSingleTop = true } })
            }
            composable(ROUTE_SETUP) {
                SetupScreen(onDone = {
                    if (!nav.popBackStack(ROUTE_STATUS, inclusive = false)) {
                        nav.navigate(ROUTE_STATUS) {
                            popUpTo(ROUTE_SETUP) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                })
            }
        }
    }
}
