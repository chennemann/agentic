package de.chennemann.agentic.ui.chat

import androidx.compose.ui.unit.dp
import de.chennemann.agentic.t3.contract.ChatAttachment
import de.chennemann.agentic.t3.contract.OrchestrationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatTimelineRowsTest {
    @Test
    fun `user messages from automations agents and T3 Code are told apart from the user's own`() {
        assertEquals(MessagePresentation("Hi", null), message(text = "Hi").presentation())
        assertEquals(
            ChatMessageOriginUi.AUTOMATION,
            message(text = "Nightly checks", scheduledTaskId = "task-1").presentation().origin,
        )
        assertEquals(
            MessagePresentation("Run the checks", ChatMessageOriginUi.AUTOMATION),
            message(
                id = "scheduled-task-message:task-1:3:scheduled",
                text = "[Triggered by schedule task: Nightly]\n\nRun the checks",
            ).presentation(),
        )
        assertNull(message(text = "[Triggered by schedule task: Nightly]\n\nRun the checks").presentation().origin)
        assertEquals(ChatMessageOriginUi.AGENT, message(createdBy = "agent", creationSource = "provider").presentation().origin)
        assertEquals(ChatMessageOriginUi.T3CODE, message(createdBy = "agent", creationSource = "server").presentation().origin)
        val guest = message(createdBy = "agent", context = Json.parseToJsonElement("""{"guest":{"name":"Ada","text":"Hi"}}""").jsonObject)
        assertNull(guest.presentation().origin)
        assertNull(message(role = "assistant", createdBy = "agent").presentation().origin)
        assertEquals("Second line", foldedMessageSummary("\n  \n Second line \nThird"))
    }

    @Test
    fun `compaction commands know whether T3 Code sent them on its own`() {
        assertTrue(message(text = " /Compact ").isCompactionCommand())
        assertFalse(message(text = "/compact", attachments = listOf(ChatAttachment(id = "a", name = "a.png", mimeType = "image/png", sizeBytes = 1))).isCompactionCommand())
        assertNull(message(text = "/compact").idleCompactionReason())
        assertEquals("onReturn", message(text = "/compact", idleCompaction = "onReturn").idleCompactionReason())
        assertEquals("beforeCacheExpires", message(text = "/compact", createdBy = "agent", creationSource = "server").idleCompactionReason())
        assertNull(message(text = "please compact", idleCompaction = "onReturn").idleCompactionReason())
    }

    @Test
    fun `compaction items read their status and token counts`() {
        assertEquals(
            ChatActivityUi.Compaction("c", "Context compacted 100K → 20K tokens"),
            compaction("""{"status":"completed","beforeTokenCount":100000,"afterTokenCount":20000}"""),
        )
        assertEquals(ChatActivityUi.Compaction("c", "Compacting context", active = true), compaction("""{"status":"running","beforeTokenCount":100000}"""))
        assertEquals(
            ChatActivityUi.Compaction("c", "Context compaction failed · Not enough messages to compact.", failed = true),
            compaction("""{"status":"failed","summary":"Not enough messages to compact."}"""),
        )
        assertEquals("Context compaction stopped", compaction("""{"status":"interrupted"}""").summary)
    }

    @Test
    fun `a compact turn reads as one row that says why T3 Code compacted`() {
        val prompt = TimedItem(
            at = "2026-10-04T10:00:00Z", turnId = "run",
            item = ChatTimelineItemUi.Message(ChatMessageUi("prompt", ChatMessageAuthorUi.USER, "/compact")),
        )
        val reasons = mapOf("prompt" to "onReturn")
        val waiting = presentCompactions(listOf(prompt), reasons, "run").single().item
        assertEquals(ChatTimelineItemUi.Activity(ChatActivityUi.Compaction("compaction:prompt", "Compacting after being idle…", active = true)), waiting)
        assertEquals("Context compaction", ((presentCompactions(listOf(prompt), mapOf("prompt" to null), null).single().item as ChatTimelineItemUi.Activity).value.summary))

        fun item(status: String, extra: String = "") = TimedItem(
            at = "2026-10-04T10:00:01Z", turnId = "run",
            item = ChatTimelineItemUi.Activity(compaction("""{"status":"$status"$extra}""")),
        )
        val running = presentCompactions(listOf(prompt, item("running")), reasons, "run")
        assertEquals(listOf("Compacting after being idle…"), running.map { (it.item as ChatTimelineItemUi.Activity).value.summary })
        val done = presentCompactions(listOf(prompt, item("completed", ""","beforeTokenCount":100000,"afterTokenCount":20000""")), reasons, null)
        val row = (done.single().item as ChatTimelineItemUi.Activity).value as ChatActivityUi.Compaction
        assertEquals("Context compacted after being idle 100K → 20K tokens", row.summary)
        assertFalse(row.active)
        val manual = presentCompactions(listOf(item("running")), emptyMap(), null).single().item as ChatTimelineItemUi.Activity
        assertEquals(ChatActivityUi.Compaction("c", "Compacting context"), manual.value)
    }

    @Test
    fun `turn diffstats come from each turn's ready checkpoint with changed files`() {
        val checkpoints = Json.parseToJsonElement(
            """[
            {"turnId":"run-1","status":"ready","files":[{"path":"a","additions":3,"deletions":1},{"path":"b","additions":2}]},
            {"turnId":"run-2","status":"ready","files":[]},
            {"turnId":"run-3","status":"pending","files":[{"path":"c","additions":9,"deletions":9}]},
            {"turnId":"run-1","status":"ready","files":[{"path":"a","additions":4,"deletions":2}]}
            ]""",
        ).jsonArray
        assertEquals(mapOf("run-1" to TurnDiffStatUi(files = 1, additions = 4, deletions = 2)), checkpoints.turnDiffStats())
    }

    @Test
    fun `token counts use compact units`() {
        assertEquals("950", formatTokenCount(950))
        assertEquals("1.5K", formatTokenCount(1_500))
        assertEquals("100K", formatTokenCount(100_000))
        assertEquals("12.3K", formatTokenCount(12_345))
        assertEquals("2M", formatTokenCount(2_000_000))
    }

    @Test
    fun `full access explanations exist only for causes the server names`() {
        assertNotNull(fullAccessOverrideExplanation("ask_rule"))
        assertNotNull(fullAccessOverrideExplanation("provider_check"))
        assertNull(fullAccessOverrideExplanation("future_cause"))
        assertNull(fullAccessOverrideExplanation(null))
    }

    @Test
    fun `pending requests use the screen while it is tall and keep a usable minimum`() {
        assertEquals(560.dp, pendingRequestsMaxHeight(900.dp))
        assertEquals(380.dp, pendingRequestsMaxHeight(600.dp))
        assertEquals(160.dp, pendingRequestsMaxHeight(250.dp))
    }

    private fun compaction(payload: String) = Json.parseToJsonElement(payload).jsonObject.toCompactionUi("c")

    private fun message(
        id: String = "message",
        role: String = "user",
        text: String = "",
        attachments: List<ChatAttachment> = emptyList(),
        context: JsonObject? = null,
        createdBy: String? = null,
        creationSource: String? = null,
        scheduledTaskId: String? = null,
        idleCompaction: String? = null,
    ) = OrchestrationMessage(
        id = id,
        turnId = "run",
        role = role,
        text = text,
        createdAt = "2026-10-04T10:00:00Z",
        updatedAt = "2026-10-04T10:00:00Z",
        attachments = attachments,
        context = context,
        createdBy = createdBy,
        creationSource = creationSource,
        scheduledTaskId = scheduledTaskId,
        idleCompaction = idleCompaction,
    )
}
