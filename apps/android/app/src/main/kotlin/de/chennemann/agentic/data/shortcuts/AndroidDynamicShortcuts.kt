package de.chennemann.agentic.data.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.R
import de.chennemann.agentic.domain.shortcuts.DynamicShortcutPublisher
import de.chennemann.agentic.domain.shortcuts.DynamicShortcutSpec
import de.chennemann.agentic.domain.shortcuts.ShortcutParseResult
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteCodec
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteInbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class AndroidDynamicShortcutPublisher(private val context: Context) : DynamicShortcutPublisher {
    override fun publish(shortcuts: List<DynamicShortcutSpec>) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val limit = manager.maxShortcutCountPerActivity.coerceAtLeast(1)
        manager.dynamicShortcuts = shortcuts.take(limit).map { spec ->
            ShortcutInfo.Builder(context, spec.id)
                .setShortLabel(spec.label.take(40))
                .setIcon(Icon.createWithResource(context, R.mipmap.agentic))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ShortcutRouteCodec.encode(spec.route)), context, MainActivity::class.java),
                )
                .build()
        }
    }
}

class AndroidShortcutRouteInbox : ShortcutRouteInbox {
    private val mutableRoutes = MutableSharedFlow<ShortcutParseResult>(replay = 1, extraBufferCapacity = 1)
    override val routes: Flow<ShortcutParseResult> = mutableRoutes
    override fun receive(result: ShortcutParseResult) {
        mutableRoutes.tryEmit(result)
    }
}
