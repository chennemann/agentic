package de.chennemann.agentic.ui.files

import de.chennemann.agentic.domain.orchestration.FileLinkTarget
import de.chennemann.agentic.domain.orchestration.inlineCodeFilePathCandidate
import de.chennemann.agentic.domain.orchestration.parseMarkdownFileLink
import de.chennemann.agentic.streamingmarkdown.MarkdownLinkHandler

/** Opens markdown links and path-shaped inline code that name files; other destinations are ignored. */
class FileLinkHandler(private val open: (FileLinkTarget) -> Unit) : MarkdownLinkHandler {
    override fun onLinkClick(href: String) {
        parseMarkdownFileLink(href)?.let(open)
    }

    override fun inlineCodeDestination(code: String): String? =
        inlineCodeFilePathCandidate(code)?.takeIf { parseMarkdownFileLink(it) != null }
}
