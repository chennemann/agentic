package de.chennemann.agentic.ui.files

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.chennemann.agentic.domain.attachments.SharedFileSource
import de.chennemann.agentic.domain.attachments.formatByteSize
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Pause
import de.chennemann.agentic.icons.Paperclip
import de.chennemann.agentic.icons.Play
import java.io.File
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * Plays a downloaded audio file. Playback follows [active]: the owner decides which player may play,
 * and [onActiveChange] reports the user's play and pause intents, completion, and leaving the screen.
 */
@Composable
fun SharedAudioPlayer(
    file: File?,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    failure: String? = null,
) {
    val currentActiveChange by rememberUpdatedState(onActiveChange)
    var prepared by remember(file) { mutableStateOf(false) }
    var playerFailed by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableIntStateOf(0) }
    val player = remember(file) {
        file?.let {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setDataSource(it.path)
                }
            }.getOrNull()
        }
    }
    DisposableEffect(player) {
        if (file != null && player == null) playerFailed = true
        player?.setOnPreparedListener {
            duration = it.duration.coerceAtLeast(0)
            prepared = true
        }
        player?.setOnCompletionListener {
            position = 0
            currentActiveChange(false)
        }
        player?.setOnErrorListener { _, _, _ ->
            playerFailed = true
            currentActiveChange(false)
            true
        }
        runCatching { player?.prepareAsync() }.onFailure { playerFailed = true }
        onDispose { player?.release() }
    }
    LaunchedEffect(player, prepared, active) {
        val current = player?.takeIf { prepared } ?: return@LaunchedEffect
        if (active) {
            if (current.currentPosition >= duration && duration > 0) current.seekTo(0)
            current.start()
            while (true) {
                position = current.currentPosition
                delay(250)
            }
        } else if (current.isPlaying) {
            current.pause()
            position = current.currentPosition
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentActiveChange(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val error = failure ?: if (playerFailed) "This audio format can't be played on this device." else null
    val busy = active && error == null && (loading || (file != null && !prepared))
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalIconButton(onClick = { onActiveChange(!active) }) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (active) Icons.Pause else Icons.Play,
                    contentDescription = if (active) "Pause audio" else if (error != null) "Retry audio" else "Play audio",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { fraction ->
                    position = (fraction * duration).toInt()
                    player?.takeIf { prepared }?.seekTo(position)
                },
                enabled = prepared && duration > 0,
                modifier = Modifier.semantics { contentDescription = "Audio position" },
            )
            Text(
                text = error ?: if (busy) "Loading…" else "${timestamp(position)} / ${if (prepared) timestamp(duration) else "--:--"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

internal fun timestamp(milliseconds: Int): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Audio a message shares, downloaded on first play and played in place. */
@Composable
fun InlineAudioCard(
    source: SharedFileSource,
    label: String,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null,
    onOpen: (() -> Unit)? = null,
) {
    val media: SharedMediaViewModel = koinViewModel()
    val loads by media.loads.collectAsStateWithLifecycle()
    val playing by media.playing.collectAsStateWithLifecycle()
    val load = loads[source]
    // A card scrolled away or closed with its message stops playing.
    DisposableEffect(source) { onDispose { media.stop(source) } }
    Surface(
        modifier = modifier.widthIn(max = 360.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open $label", onClick = onOpen) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Paperclip, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    listOfNotNull("Audio", sizeBytes?.let(::formatByteSize)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SharedAudioPlayer(
                file = (load as? SharedFileLoadUi.Loaded)?.file?.file,
                active = playing == source,
                onActiveChange = { if (it) media.play(source) else media.stop(source) },
                loading = load is SharedFileLoadUi.Loading,
                failure = (load as? SharedFileLoadUi.Failed)?.message,
            )
        }
    }
}
