package de.chennemann.agentic.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

@Composable
internal fun rememberAppEntryDecorators() = listOf(
    rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
    rememberViewModelStoreNavEntryDecorator<NavKey>()
)

@Composable
internal fun rememberAppBackStack(target: NavKey) = rememberNavBackStack(OnboardingRoute).also { stack ->
    LaunchedEffect(target) {
        // Authentication selects the root; a restored detail screen must stay on top.
        if (stack.firstOrNull() != target) {
            stack.clear()
            stack.add(target)
        }
    }
}
