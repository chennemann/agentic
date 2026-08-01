package de.chennemann.agentic

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.chennemann.agentic.domain.connection.ConnectionSupervisor
import de.chennemann.agentic.navigation.AppNavHost
import de.chennemann.agentic.ui.theme.MobileTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val connectionSupervisor: ConnectionSupervisor by inject()

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
            MobileTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.exclude(WindowInsets.ime),
                            ),
                    ) {
                        AppNavHost()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectionSupervisor.wake()
    }
}
