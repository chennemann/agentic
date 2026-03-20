package de.chennemann.agentic

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.chennemann.agentic.navigation.AppNavHost
import de.chennemann.agentic.ui.theme.MobileTheme

class MainActivity : ComponentActivity() {
    private val requestInternet = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        if (checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            requestInternet.launch(Manifest.permission.INTERNET)
        }
        setContent {
            MobileTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}
