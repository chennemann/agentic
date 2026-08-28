package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState

internal class ChatTurnFlingBehavior(
    private val listState: LazyListState,
    private val userMessageIndices: List<Int>,
    private val minimumVelocity: Float,
    private val nativeFlingBehavior: FlingBehavior,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val targetIndex = fastUpwardTurnBoundaryTarget(
            velocityY = -initialVelocity,
            minimumVelocityY = minimumVelocity,
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            userMessageIndices = userMessageIndices,
        ) ?: return with(nativeFlingBehavior) {
            this@performFling.performFling(initialVelocity)
        }

        val scrollScope = TurnBoundaryScrollScope(
            delegate = this,
            distanceToBoundary = { listState.distanceToItemStart(targetIndex) },
        )
        return with(nativeFlingBehavior) {
            scrollScope.performFling(initialVelocity)
        }
    }
}

private class TurnBoundaryScrollScope(
    private val delegate: ScrollScope,
    private val distanceToBoundary: () -> Float?,
) : ScrollScope {
    override fun scrollBy(pixels: Float): Float {
        val distanceBeforeScroll = distanceToBoundary()
        if (pixels < 0f && distanceBeforeScroll != null && distanceBeforeScroll > AlignmentTolerancePx) {
            return delegate.scrollBy(distanceBeforeScroll)
        }

        val limitedDelta = limitDeltaToTurnBoundary(
            requestedDelta = pixels,
            distanceToBoundary = distanceBeforeScroll,
        )
        val consumed = delegate.scrollBy(limitedDelta)
        val distanceAfterScroll = distanceToBoundary()
        val correction = distanceAfterScroll
            ?.takeIf { pixels < 0f && it > AlignmentTolerancePx }
            ?.let(delegate::scrollBy)
            ?: 0f
        return consumed + correction
    }
}

private fun LazyListState.distanceToItemStart(index: Int): Float? {
    val layoutInfo = layoutInfo
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val desiredOffset = if (index == 0) layoutInfo.beforeContentPadding else 0
    return (item.offset - desiredOffset).toFloat()
}

private const val AlignmentTolerancePx = 0.5f
