package de.chennemann.agentic.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.chennemann.agentic.ui.chat.VoiceInputStatusUi

@Composable
internal fun rememberMicrophonePermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    var recovery by remember { mutableStateOf<PermissionRecovery?>(null) }
    val currentOnGranted by rememberUpdatedState(onGranted)
    val currentOnDenied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            recovery = null
            currentOnGranted()
        } else {
            currentOnDenied()
            if (
                activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO,
                )
            ) {
                recovery = PermissionRecovery.APP_SETTINGS
            }
        }
    }

    when (recovery) {
        PermissionRecovery.RATIONALE -> {
            AlertDialog(
                onDismissRequest = { recovery = null },
                title = { Text("Microphone permission") },
                text = {
                    Text(
                        "Agentic needs microphone access only while recording voice input " +
                            "for transcription.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            recovery = null
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    ) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { recovery = null }) {
                        Text("Not now")
                    }
                },
            )
        }

        PermissionRecovery.APP_SETTINGS -> {
            AlertDialog(
                onDismissRequest = { recovery = null },
                title = { Text("Enable microphone access") },
                text = {
                    Text(
                        "Microphone permission is disabled. Enable it in Android app settings " +
                            "to use voice input.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            recovery = null
                            context.openApplicationSettings()
                        },
                    ) {
                        Text("Open app settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { recovery = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        null -> Unit
    }

    return {
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> currentOnGranted()

            activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO,
                ) -> recovery = PermissionRecovery.RATIONALE

            else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
internal fun VoiceRecordingLifecycleEffect(
    status: VoiceInputStatusUi,
    onRecordingCancelled: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentStatus by rememberUpdatedState(status)
    val currentOnRecordingCancelled by rememberUpdatedState(onRecordingCancelled)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                currentStatus == VoiceInputStatusUi.RECORDING
            ) {
                currentOnRecordingCancelled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (currentStatus == VoiceInputStatusUi.RECORDING) {
                currentOnRecordingCancelled()
            }
        }
    }
}

private enum class PermissionRecovery {
    RATIONALE,
    APP_SETTINGS,
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openApplicationSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
