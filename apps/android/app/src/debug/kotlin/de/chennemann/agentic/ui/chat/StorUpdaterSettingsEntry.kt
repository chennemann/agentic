package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

fun LazyListScope.StorUpdaterSettingsEntry(onClick: () -> Unit) {
    item("stor-updater") {
        TextButton(onClick = onClick) {
            Text("Development updates")
        }
    }
}
