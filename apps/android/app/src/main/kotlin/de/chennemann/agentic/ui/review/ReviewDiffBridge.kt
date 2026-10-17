package de.chennemann.agentic.ui.review

import de.chennemann.agentic.streamingmarkdown.CodeLexerRegistry
import de.chennemann.agentic.streamingmarkdown.CodeTokenKind
import de.chennemann.agentic.ui.code.codeLanguageFor
import expo.modules.t3reviewdiff.T3ReviewDiffView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ReviewPayload(val rows: String, val tokens: String)
private val RowJson = Json { encodeDefaults = true }

internal fun reviewPayload(
    document: ReviewDocument,
    colors: Map<CodeTokenKind, String>,
    italic: Set<CodeTokenKind> = emptySet()
): ReviewPayload {
    val tokens = buildJsonObject {
        document.files.forEach { file ->
            val language = codeLanguageFor(file.path)
            file.rows.filter { it.kind == "line" }.forEach { row ->
                put(
                    row.id,
                    buildJsonArray {
                        CodeLexerRegistry.Default.tokenize(language, row.content).forEach { token ->
                            add(
                                buildJsonObject {
                                    put("content", token.value)
                                    put("color", colors.getValue(token.kind))
                                    put("fontStyle", if (token.kind in italic) 1 else 0)
                                }
                            )
                        }
                    }
                )
            }
        }
    }
    return ReviewPayload(RowJson.encodeToString(document.rows), tokens.toString())
}

/** Avoid sending/resetting large native payloads during file-selection or scroll callbacks. */
internal class ReviewDiffBinding {
    private var currentView: T3ReviewDiffView? = null
    private val applied = mutableMapOf<String, String>()

    fun bind(
        view: T3ReviewDiffView,
        key: String,
        appearance: ReviewAppearance,
        payload: ReviewPayload,
        collapsed: List<String>,
        viewed: List<String>
    ) {
        if (currentView !== view) {
            currentView = view
            applied.clear()
        }
        update("theme", appearance.theme, view::setThemeJson)
        update("style", appearance.style, view::setStyleJson)
        update("key", key) {
            view.setContentResetKey(it)
            view.setTokensResetKey(it)
        }
        update("rows", payload.rows, view::setRowsJson)
        update("tokens", payload.tokens, view::setTokensJson)
        update("collapsed", RowJson.encodeToString(collapsed), view::setCollapsedFileIdsJson)
        update("viewed", RowJson.encodeToString(viewed), view::setViewedFileIdsJson)
    }

    fun release() {
        currentView = null
        applied.clear()
    }

    private fun update(name: String, value: String, apply: (String) -> Unit) {
        if (applied[name] != value) {
            applied[name] = value
            apply(value)
        }
    }
}
