package de.chennemann.agentic.ui.updater

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import de.chennemann.agentic.ui.theme.MobileTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class StorUpdaterScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun missing_certificate_disables_check_and_revoked_alias_allows_reselection() {
        val preferences = GlobalContext.get().get<InterfacePreferencesRepository>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ViewModelStore()
        runBlocking { preferences.setStorCertificateAlias(null) }
        val controller = StorUpdaterViewModel(context, preferences)
        store.put("updater", controller)
        try {
            compose.setContent { MobileTheme { StorUpdaterScreen(onBack = {}, controller = controller) } }
            compose.waitUntil(5_000) { controller.ready }
            compose.onNodeWithText("Select certificate").assertIsEnabled()
            compose.onNodeWithText("Check for updates").assertIsNotEnabled()
            runBlocking { preferences.setStorCertificateAlias("removed-test-identity") }
            // A new owner restores only the alias, never key material.
            val recreated = StorUpdaterViewModel(context, preferences)
            store.put("recreated", recreated)
            compose.waitUntil(5_000) { recreated.ready }
            compose.runOnIdle { recreated.checkForUpdate() }
            compose.waitUntil(10_000) { !recreated.state.checking }
            org.junit.Assert.assertTrue(recreated.state.status.contains("select the certificate again"))
            compose.onNodeWithText("Select certificate").performClick()
            // The actual system chooser opens; dismissal leaves settings usable.
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
        } finally {
            compose.runOnIdle { store.clear() }
            runBlocking { preferences.setStorCertificateAlias(null) }
        }
    }
}
