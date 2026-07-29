package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatUiModelsTest {
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
    fun `approval model supports every portable decision`() {
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
