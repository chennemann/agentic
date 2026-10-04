package de.chennemann.agentic.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppBackStackTest {
    private class EntryModel : ViewModel() {
        var released = false
        override fun onCleared() {
            released = true
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun leavingTerminalReleasesItsViewModelAndReopeningCreatesAFreshOne() {
        lateinit var stack: MutableList<NavKey>
        lateinit var model: EntryModel
        compose.setContent {
            val backStack = rememberAppBackStack(ChatRoute)
            stack = backStack
            NavDisplay(
                backStack = backStack,
                entryDecorators = rememberAppEntryDecorators(),
                entryProvider = { key ->
                    NavEntry(key) {
                        if (key is TerminalRoute) model = viewModel { EntryModel() }
                    }
                }
            )
        }
        compose.runOnIdle { stack.add(TerminalRoute("test-thread")) }
        compose.waitForIdle()
        val first = compose.runOnIdle { model }
        assertFalse(first.released)
        compose.runOnIdle { stack.removeLast() }
        compose.waitUntil(15000) { first.released }
        compose.runOnIdle { stack.add(TerminalRoute("test-thread")) }
        compose.waitForIdle()
        assertNotSame(first, model)
        assertTrue(first.released)
    }

    @Test
    fun terminalSurvivesStateRestorationAndSignOutStillClearsIt() {
        val restoration = StateRestorationTester(compose)
        val target = mutableStateOf<NavKey>(ChatRoute)
        lateinit var stack: MutableList<NavKey>
        restoration.setContent { stack = rememberAppBackStack(target.value) }
        val terminal = TerminalRoute("test-thread")
        compose.runOnIdle { stack.add(terminal) }

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertEquals(listOf(ChatRoute, terminal), stack.toList())
            target.value = OnboardingRoute
        }
        compose.runOnIdle { assertEquals(listOf(OnboardingRoute), stack.toList()) }
    }
}
