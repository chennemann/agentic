package de.chennemann.agentic.ui.usage

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UsageScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resetRequiresConfirmationAndCancelDoesNotRedeem() {
        var redeemed: String? = null
        val account =
            LimitAccountUi(
                "credit-1", "My provider", "Subscription", null, "Server",
                emptyList(), null, "1 reset credit", true
            )
        val state =
            UsageUiState(
                metric = UsageMetric.LIMITS,
                environments = listOf(UsageEnvironmentUi("env", "Server", true, "Connected", true, emptyList(), false)),
                accounts = listOf(account)
            )
        compose.setContent {
            MaterialTheme { UsageScreen(state, {}, {}, {}, {}, {}, {}, { redeemed = it }, { _, _, _, _ -> }, {}) }
        }
        compose.onNodeWithText("Redeem token reset").performClick()
        compose.onNodeWithText("Redeem a token reset?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(null, redeemed)
        compose.onNodeWithText("Redeem token reset").performClick()
        compose.onNodeWithText("Redeem reset").performClick()
        assertEquals("credit-1", redeemed)
    }

    @Test fun readOnlyAccountCannotRedeemAndDetailsArePrivateUntilRevealed() {
        val account =
            LimitAccountUi(
                "account", "My provider", null, "account@example.test", "Server",
                emptyList(), null, "1 reset credit", false
            )
        val state = UsageUiState(metric = UsageMetric.LIMITS, accounts = listOf(account))
        compose.setContent { MaterialTheme { UsageScreen(state, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _ -> }, {}) } }
        compose.onNodeWithText("account@example.test").assertDoesNotExist()
        compose.onNodeWithText("Redeem token reset").assertIsNotEnabled()
        compose.onNodeWithText("Reveal account").performClick()
        compose.onNodeWithText("account@example.test").assertIsDisplayed()
    }
}
