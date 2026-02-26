package de.chennemann.opencode.mobile.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppNavHostNavigationPolicyTest {
    @Test
    fun navigateToAgentChatRouteReturnsToRootEntry() {
        val stack = mutableListOf<NavKey>(AgentChatRoute, WorkspaceHubRoute, LogsRoute)

        dispatchNavAction(stack, NavEvent.NavigateTo(AgentChatRoute))

        assertEquals(listOf(AgentChatRoute), stack)
    }

    @Test
    fun navigateToAgentChatRouteAddsRootWhenMissing() {
        val stack = mutableListOf<NavKey>(WorkspaceHubRoute, LogsRoute)

        dispatchNavAction(stack, NavEvent.NavigateTo(AgentChatRoute))

        assertEquals(listOf(AgentChatRoute), stack)
    }

    @Test
    fun navigateToWorkspaceHubAndLogsAppendsEntries() {
        val stack = mutableListOf<NavKey>(AgentChatRoute)

        dispatchNavAction(stack, NavEvent.NavigateTo(WorkspaceHubRoute))
        dispatchNavAction(stack, NavEvent.NavigateTo(LogsRoute))

        assertEquals(listOf(AgentChatRoute, WorkspaceHubRoute, LogsRoute), stack)
    }

    @Test
    fun navigateToQuickSwitchSheetReplacesExistingSheet() {
        val stack = mutableListOf<NavKey>(AgentChatRoute, SessionSelectionBottomSheetRoute("/repo/one"))

        dispatchNavAction(stack, NavEvent.NavigateTo(SessionSelectionBottomSheetRoute("/repo/two")))

        assertEquals(listOf(AgentChatRoute, SessionSelectionBottomSheetRoute("/repo/two")), stack)
    }

    @Test
    fun navigateBackRemovesTopOnly() {
        val stack = mutableListOf<NavKey>(AgentChatRoute, WorkspaceHubRoute, LogsRoute)

        dispatchNavAction(stack, NavEvent.NavigateBack)

        assertEquals(listOf(AgentChatRoute, WorkspaceHubRoute), stack)
    }
}
