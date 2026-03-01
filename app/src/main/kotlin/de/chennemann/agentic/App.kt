package de.chennemann.agentic

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.StrictMode
import de.chennemann.agentic.di.AppScopeName
import de.chennemann.agentic.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.core.context.startKoin

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            check(Looper.getMainLooper().thread === Thread.currentThread()) {
                "App.onCreate must run on main thread"
            }
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        startKoin {
            androidContext(this@App)
            modules(appModule)
        }
    }

    override fun onTerminate() {
        getKoin().getOrNull<CoroutineScope>(named(AppScopeName))?.cancel()
        super.onTerminate()
    }
}
