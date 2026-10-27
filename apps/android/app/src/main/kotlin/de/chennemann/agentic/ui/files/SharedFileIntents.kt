package de.chennemann.agentic.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import de.chennemann.agentic.R
import de.chennemann.agentic.domain.attachments.SharedFile

/** Grants other apps read access to downloaded shared files, apart from any library's provider. */
class SharedFileProvider : FileProvider(R.xml.shared_file_paths)

private fun Context.sharedFileUri(file: SharedFile): Uri =
    FileProvider.getUriForFile(this, "$packageName.sharedfiles", file.file, file.source.name)

/** Hands the downloaded file to another app that can view its type. */
fun Context.openSharedFileWith(file: SharedFile) {
    val uri = sharedFileUri(file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launch(Intent.createChooser(intent, "Open ${file.source.name} with"))
}

fun Context.shareSharedFile(file: SharedFile) {
    val uri = sharedFileUri(file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(file.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(contentResolver, file.source.name, uri)
    launch(Intent.createChooser(intent, "Share ${file.source.name}"))
}

private fun Context.launch(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this file.", Toast.LENGTH_SHORT).show()
    }
}
