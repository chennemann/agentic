package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatUiModelsTest {
    @Test
    fun `async answer message and activity have distinct stable timeline keys`() {
        val sharedId = "async-answer:request-1"
        val message = ChatTimelineItemUi.Message(
            ChatMessageUi(sharedId, ChatMessageAuthorUi.USER, "Selected answer"),
        )
        val activity = ChatTimelineItemUi.Activity(
            ChatActivityUi.Information(sharedId, "User input submitted"),
        )
        val toolGroup = ChatTimelineItemUi.ToolGroup(sharedId, emptyList())
        val items = listOf(message, activity, toolGroup)

        assertEquals(3, items.map { it.lazyKey }.toSet().size)
        assertEquals(sharedId, message.value.id)
        assertEquals(sharedId, activity.value.id)
        assertEquals(message.lazyKey, message.copy(value = message.value.copy(content = "Updated answer")).lazyKey)
        assertEquals(activity.lazyKey, activity.copy(value = ChatActivityUi.Information(sharedId, "Updated summary")).lazyKey)
    }

    @Test
    fun `saved and reconnecting conversations allow a manual connection retry`() {
        assertTrue(ChatConnectionUi.Cached().canRetryConnection)
        assertTrue(ChatConnectionUi.Reconnecting().canRetryConnection)
    }

    @Test
    fun `unknown activity keeps its server summary and safe detail`() {
        val activity = ChatActivityUi.Unknown(
            id = "activity-1",
            summary = "Unrecognized work",
            typeLabel = "future.activity",
            formattedDetail = """{"result":"kept"}""",
        )

        assertEquals("Unrecognized work", activity.summary)
        assertEquals("""{"result":"kept"}""", activity.formattedDetail)
        assertEquals("future.activity", activity.typeLabel)
    }

    @Test
    fun `approval model supports every server decision`() {
        val approval = PendingApprovalUi(
            requestId = "approval-1",
            title = "Continue?",
            description = null,
            decisions = ApprovalDecisionUi.entries,
        )

        assertEquals(
            setOf(
                ApprovalDecisionUi.ACCEPT,
                ApprovalDecisionUi.ACCEPT_FOR_SESSION,
                ApprovalDecisionUi.DECLINE,
                ApprovalDecisionUi.CANCEL,
            ),
            approval.decisions.toSet(),
        )
    }

    @Test
    fun `provider option routes by stable instance id without provider coupling`() {
        val option = ProviderModelOptionUi(
            id = "instance-7/model-a",
            providerInstanceId = "instance-7",
            providerLabel = "Team provider",
            modelLabel = "Model A",
        )

        assertEquals("instance-7", option.providerInstanceId)
        assertTrue(option.id.startsWith(option.providerInstanceId))
    }
}
