package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatTimelinePresentationTest {
    @Test
    fun `completed HTML replaces tool lifecycle and stays visible in a settled turn`() {
        val page = de.chennemann.agentic.domain.attachments.HtmlRenderPage("page.html", "Gallery", 500)
        val started = tool("start", ActivityStatusUi.RUNNING)
        val completed = tool("end", ActivityStatusUi.COMPLETED).let { entry ->
            val tool = (entry.item as ChatTimelineItemUi.Activity).value as ChatActivityUi.Tool
            entry.copy(item = ChatTimelineItemUi.Activity(tool.copy(htmlRender = page)))
        }
        val published = publishHtmlRenders(collapseToolLifecycles(listOf(started, completed)))
        assertEquals(ChatTimelineItemUi.HtmlRender("start", page), published.single().item)
        val folded = foldSettledTurns(published + tool("other", ActivityStatusUi.COMPLETED), "turn", null, null, null, false)
        assertTrue(folded.any { it is ChatTimelineItemUi.HtmlRender })
        assertTrue(folded.any { it is ChatTimelineItemUi.WorkSummary })
        val failed = completed.copy(item = ChatTimelineItemUi.Activity(((completed.item as ChatTimelineItemUi.Activity).value as ChatActivityUi.Tool).copy(status = ActivityStatusUi.FAILED)))
        assertTrue(publishHtmlRenders(listOf(failed)).single().item is ChatTimelineItemUi.Activity)
    }
    @Test
    fun `ordering compares instants rather than timestamp spellings`() {
        assertEquals(timelineInstant("2026-10-04T12:00:00+02:00"), timelineInstant("2026-10-04T10:00:00Z"))
        assertTrue(timelineInstant("2026-10-04T10:00:00.001Z") > timelineInstant("2026-10-04T10:00:00Z"))
    }

    private fun message(id: String, author: ChatMessageAuthorUi) = TimedItem(
        at = "2026-10-04T10:00:00Z", turnId = "turn",
        item = ChatTimelineItemUi.Message(ChatMessageUi(id, author, id)),
    )

    private fun tool(id: String, status: ActivityStatusUi) = TimedItem(
        at = "2026-10-04T10:00:01Z", turnId = "turn",
        item = ChatTimelineItemUi.Activity(ChatActivityUi.Tool(id, "Command", status = status, lifecycleKey = "turn:call")),
    )

    @Test
    fun `tool completion across commentary retains anchor and failure`() {
        val items = collapseToolLifecycles(listOf(tool("start", ActivityStatusUi.RUNNING), message("update", ChatMessageAuthorUi.ASSISTANT), tool("end", ActivityStatusUi.FAILED)))
        assertEquals(2, items.size)
        val result = (items.first().item as ChatTimelineItemUi.Activity).value as ChatActivityUi.Tool
        assertEquals("start", result.id)
        assertEquals(ActivityStatusUi.FAILED, result.status)
    }

    @Test
    fun `agent work remains visible when the parent turn settles`() {
        val agent = tool("agent", ActivityStatusUi.RUNNING).let { entry ->
            val activity = (entry.item as ChatTimelineItemUi.Activity).value as ChatActivityUi.Tool
            entry.copy(item = ChatTimelineItemUi.Activity(activity.copy(isAgent = true, lifecycleKey = "agent-call")))
        }
        val folded = foldSettledTurns(listOf(agent), "turn", null, null, null, false)
        assertTrue(folded.single() is ChatTimelineItemUi.ToolGroup)
        assertEquals(ActivityStatusUi.RUNNING, (folded.single() as ChatTimelineItemUi.ToolGroup).activities.single().status)
    }

    @Test
    fun `settled work folds with duration while first and last messages stay visible`() {
        val items = listOf(message("user", ChatMessageAuthorUi.USER), message("first", ChatMessageAuthorUi.ASSISTANT), tool("tool", ActivityStatusUi.COMPLETED), message("middle", ChatMessageAuthorUi.ASSISTANT), message("last", ChatMessageAuthorUi.ASSISTANT))
        val folded = foldSettledTurns(items, "turn", null, "2026-10-04T10:00:00Z", "2026-10-04T10:01:02Z", false)
        assertEquals(listOf("user", "first", "turn", "last"), folded.map { it.id })
        val summary = folded[2] as ChatTimelineItemUi.WorkSummary
        assertEquals("Worked for 1m 2s", summary.label)
        assertEquals(2, summary.items.size)
        assertFalse(foldSettledTurns(items, "turn", "turn", null, null, false).any { it is ChatTimelineItemUi.WorkSummary })
    }
}
