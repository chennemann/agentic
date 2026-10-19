package de.chennemann.agentic.ui.pullrequests

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.chennemann.agentic.ui.review.parseReviewDiff
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PullRequestsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(PullRequestsUiState())
    private var opened: String? = null
    private var action: String? = null
    private var filter: String? = null
    private var comment: String? = null
    private var changes = 0

    private fun show() {
        compose.setContent { MobileTheme {
            PullRequestsScreen(state.value, {}, {}, { filter = it }, {}, {}, {}, {}, { opened = it }, {}, { changes++ },
                { comment = it }, { _, _ -> }, { action = it }, { _, _ -> }, { _, _ -> }, {}, {})
        } }
    }

    @Test fun listFiltersAndSelection() {
        state.value = PullRequestsUiState(
            environments = listOf(PrEnvironmentUi("env", "Development")), environmentId = "env",
            rows = listOf(PrRowUi("github.com/owner/repo/42", "Improve workspace tools", "owner/repo #42 - alex", "open / passing", "android")))
        show()
        compose.onNodeWithText("Merged").performClick()
        assertEquals("merged", filter)
        compose.onNodeWithTag("pull-request-row").performClick()
        assertEquals("github.com/owner/repo/42", opened)
    }

    @Test fun mutationsRequireConfirmation() {
        state.value = detailState()
        show()
        saveScreenshot("pull-request-detail")
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Close pull request").performClick()
        assertEquals(null, action)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(null, action)
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Close pull request").performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals("close", action)
    }

    @Test fun failedCommentKeepsDraftAndSuccessfulSubmissionClearsIt() {
        state.value = detailState()
        show()
        compose.onNodeWithText("Write a comment or review").performScrollTo().performTextInput("Please check this edge case")
        compose.onNodeWithText("Comment", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("Please check this edge case", comment)
        compose.runOnIdle { state.value = state.value.copy(actionError = "Request failed") }
        compose.onNodeWithText("Please check this edge case").assertExists()
        compose.runOnIdle { state.value = state.value.copy(actionError = null, submitted = 1) }
        compose.onNodeWithText("Please check this edge case").assertDoesNotExist()
    }

    @Test fun refreshDuringPatchLoadingRequestsTheNewPatch() {
        state.value = detailState()
        show()
        compose.onNodeWithText("Changes").performClick()
        compose.waitForIdle()
        assertEquals(1, changes)
        compose.runOnIdle { state.value = state.value.copy(detailLoading = true) }
        compose.waitForIdle()
        compose.runOnIdle { state.value = state.value.copy(detailLoading = false) }
        compose.waitForIdle()
        assertEquals(2, changes)
    }

    @Test fun patchTabUsesNativeDiffSurface() {
        state.value = detailState().copy(diffLoaded = true, document = parseReviewDiff(
            "diff --git a/Main.kt b/Main.kt\n--- a/Main.kt\n+++ b/Main.kt\n@@ -1 +1 @@\n-old\n+new\n"))
        show()
        compose.onNodeWithText("Changes").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pull-request-diff").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pull-request-diff").assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun detailState() = PullRequestsUiState(
        environments = listOf(PrEnvironmentUi("env", "Development")), environmentId = "env", selectedId = "pr42", canWrite = true,
        activityLoaded = true,
        detail = PrDetailUi("Improve workspace tools", "owner/repo #42 - feature to main", "A concise description.",
            "https://example.test/owner/repo/pull/42", "open / mergeable / +2 -1 / 1 files", emptyList(), "android", "reviewer",
            listOf(PrActionUi("close", "Close pull request", "close")), emptyList(), true, true))
}
