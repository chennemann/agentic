package de.chennemann.agentic

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.content.Intent
import android.os.Looper
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.chennemann.agentic.domain.connection.ConnectionSupervisor
import de.chennemann.agentic.domain.sharing.SharedTextImportRepository
import de.chennemann.agentic.domain.sharing.SharedTextIntentParser
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteCodec
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteInbox
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import de.chennemann.agentic.domain.preferences.ThemePreference
import de.chennemann.agentic.navigation.AppNavHost
import de.chennemann.agentic.ui.theme.MobileTheme
import de.chennemann.agentic.ui.theme.LocalCodeScale
import org.koin.android.ext.android.inject
import de.chennemann.agentic.domain.connection.AppVisibility
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.isSystemInDarkTheme

class MainActivity : ComponentActivity() {
    private val appVisibility: AppVisibility by inject()

    override fun onStop() {
        appVisibility.setVisible(false)
        super.onStop()
    }
    private val connectionSupervisor: ConnectionSupervisor by inject()
    private val sharedTextImports: SharedTextImportRepository by inject()
    private val shortcutRoutes: ShortcutRouteInbox by inject()
    private val interfacePreferences: InterfacePreferencesRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            check(Looper.getMainLooper().thread === Thread.currentThread()) {
                "MainActivity.onCreate must run on main thread"
            }
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build(),
            )
        }
        setContent {
            val preferences by interfacePreferences.preferences.collectAsStateWithLifecycle(
                initialValue = de.chennemann.agentic.domain.preferences.InterfacePreferences(),
            )
            val systemDark = isSystemInDarkTheme()
            val dark = when (preferences.theme) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * preferences.interfaceScale, density.fontScale),
                LocalCodeScale provides preferences.codeScale,
            ) {
            MobileTheme(darkTheme = dark, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.systemBars.union(WindowInsets.displayCutout),
                            ),
                    ) {
                        AppNavHost()
                    }
                }
            }
            }
        }
        receiveShare(intent)
        receiveShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
        receiveShortcut(intent)
    }

    private fun receiveShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val result = SharedTextIntentParser.parse(
            action = intent.action,
            type = intent.type,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
        )
        lifecycleScope.launch { sharedTextImports.receive(result) }
    }

    private fun receiveShortcut(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        shortcutRoutes.receive(ShortcutRouteCodec.parse(intent.dataString))
        connectionSupervisor.wake()
    }

    override fun onStart() {
        super.onStart()
        appVisibility.setVisible(true)
        connectionSupervisor.wake()
    }
}
