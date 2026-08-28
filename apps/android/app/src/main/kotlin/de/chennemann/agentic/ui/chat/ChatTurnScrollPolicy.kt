package de.chennemann.agentic.ui.chat

internal fun fastUpwardTurnBoundaryTarget(
    velocityY: Float,
    minimumVelocityY: Float,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    userMessageIndices: List<Int>,
    alignmentTolerancePx: Int = 8,
): Int? {
    if (velocityY < minimumVelocityY) return null
    val includeFirstVisibleItem = firstVisibleItemScrollOffset > alignmentTolerancePx
    return userMessageIndices.lastOrNull { index ->
        index < firstVisibleItemIndex || includeFirstVisibleItem && index == firstVisibleItemIndex
    }
}

internal fun limitDeltaToTurnBoundary(
    requestedDelta: Float,
    distanceToBoundary: Float?,
): Float {
    if (requestedDelta >= 0f || distanceToBoundary == null) return requestedDelta
    return maxOf(requestedDelta, distanceToBoundary.coerceAtMost(0f))
}
