package de.chennemann.agentic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Close
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.MagnifyingGlass
import de.chennemann.agentic.ui.chat.ThreadActivityKind
import de.chennemann.agentic.ui.chat.label
import de.chennemann.agentic.ui.chat.rememberTickingNow

/** The drawer's entry into thread search, styled as a search field. */
@Composable
fun ThreadSearchEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.MagnifyingGlass,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Search threads and messages",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSearchScreen(
    state: ThreadSearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenThread: (String) -> Unit,
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query, TextRange(0, state.query.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                title = {
                    TextField(
                        value = field,
                        onValueChange = {
                            field = it
                            onQueryChange(it.text)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text("Search threads and messages") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (field.text.isNotEmpty()) {
                                IconButton(onClick = { field = TextFieldValue(""); onQueryChange("") }) {
                                    Icon(Icons.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.searchingMessages || state.opening) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                state.error?.let { error -> item("error") { Notice(error, error = true) } }
                if (state.idle) {
                    item("hint") { Notice("Find a thread by its title or project, or by words from its messages.") }
                }
                if (state.titleResults.isNotEmpty()) {
                    item("titles") { SectionHeader("Threads") }
                    items(state.titleResults, key = { "title:${it.threadId}" }) { result ->
                        ResultRow(result) { onOpenThread(result.threadId) }
                    }
                }
                if (state.messageResults.isNotEmpty()) {
                    item("messages") { SectionHeader("In messages") }
                    items(state.messageResults, key = { "message:${it.threadId}" }) { result ->
                        ResultRow(result) { onOpenThread(result.threadId) }
                    }
                }
                state.notice?.let { notice -> item("notice") { Notice(notice) } }
                if (!state.idle && !state.searchingMessages && state.titleResults.isEmpty() && state.messageResults.isEmpty()) {
                    item("empty") { Notice("No matching threads") }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun ResultRow(result: ThreadSearchResultUi, onClick: () -> Unit) {
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                highlighted(result.title, result.titleHighlights, highlight),
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.settled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            result.activity?.let { activity ->
                val now = rememberTickingNow(activity.since != null)
                Text(
                    activity.label(now),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activity.kind == ThreadActivityKind.WORKING) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        result.snippet?.let { snippet ->
            Text(
                buildAnnotatedString {
                    pushStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Medium,
                            color = if (snippet.fromUser) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                        ),
                    )
                    append("${snippet.author}: ")
                    pop()
                    append(highlighted(snippet.text, snippet.highlights, highlight))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                result.projectLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.settled) {
                Spacer(Modifier.size(2.dp))
                Text("Settled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.filter { it.first >= 0 && it.last < text.length }.forEach { addStyle(style, it.first, it.last + 1) }
}
