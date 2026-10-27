package de.chennemann.agentic.navigation

import de.chennemann.agentic.domain.attachments.SharedFileSource
import de.chennemann.agentic.domain.orchestration.FileLinkTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SharedFileRoutesTest {
    @Test
    fun `media and documents open in the shared viewer while text keeps its line target`() {
        assertEquals(
            SharedFileRoute(name = "take.mp3", threadId = "thread-1", path = "/Users/me/take.mp3"),
            fileLinkRoute("thread-1", FileLinkTarget("/Users/me/take.mp3")),
        )
        assertEquals(
            WorkspaceFilesRoute("thread-1", "src/App.kt", 12, 3),
            fileLinkRoute("thread-1", FileLinkTarget("src/App.kt", 12, 3)),
        )
    }

    @Test
    fun `routes restore the shared file they were opened for`() {
        val sources = listOf(
            SharedFileSource.Attachment("att-1", "report.pdf", "application/pdf"),
            SharedFileSource.HostPath("thread-1", "~/Downloads/diagram.svg"),
        )
        sources.forEach { assertEquals(it, it.route().source()) }
    }
}
