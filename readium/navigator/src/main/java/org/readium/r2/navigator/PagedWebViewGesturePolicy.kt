/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator

import kotlin.math.abs
import kotlin.math.roundToInt

private const val PAGE_DRAG_TURN_THRESHOLD_FRACTION = 0.25f

internal enum class PagedDragEnd {
    DragRelease,
    TapRelease,
    Cancel,
}

internal data class PagedDragGesture(
    val currentPage: Int,
    val pageCount: Int,
    val initialVelocity: Int,
    val currentVelocity: Int,
    val deltaX: Int,
    val deltaY: Int,
    val pageWidth: Int,
    val flingDistance: Int,
    val minimumVelocity: Int,
    val touchSlop: Int,
    val end: PagedDragEnd,
)

internal sealed interface PagedDragSettleAction {
    data object Ignore : PagedDragSettleAction
    data object SnapToCurrentPage : PagedDragSettleAction
    data object PreviousResource : PagedDragSettleAction
    data object NextResource : PagedDragSettleAction
    data class Page(val targetPage: Int) : PagedDragSettleAction
}

internal object PagedWebViewGesturePolicy {

    fun settleAction(gesture: PagedDragGesture): PagedDragSettleAction {
        if (gesture.end == PagedDragEnd.Cancel) {
            return PagedDragSettleAction.SnapToCurrentPage
        }

        val targetPage = targetPageForPagedDrag(
            currentPage = gesture.currentPage,
            initialVelocity = gesture.initialVelocity,
            currentVelocity = gesture.currentVelocity,
            deltaX = gesture.deltaX,
            pageWidth = gesture.pageWidth,
            flingDistance = gesture.flingDistance,
            minimumVelocity = gesture.minimumVelocity,
        )

        val movedBeyondTapSlop = abs(gesture.deltaX) > gesture.touchSlop.coerceAtLeast(1) ||
            abs(gesture.deltaY) > gesture.touchSlop.coerceAtLeast(1)
        return when {
            targetPage == gesture.currentPage &&
                gesture.end == PagedDragEnd.TapRelease &&
                !movedBeyondTapSlop ->
                PagedDragSettleAction.Ignore
            targetPage == gesture.currentPage -> PagedDragSettleAction.SnapToCurrentPage
            targetPage < 0 -> PagedDragSettleAction.PreviousResource
            targetPage >= gesture.pageCount -> PagedDragSettleAction.NextResource
            else -> PagedDragSettleAction.Page(targetPage)
        }
    }

    fun targetPageForPagedDrag(
        currentPage: Int,
        initialVelocity: Int,
        currentVelocity: Int,
        deltaX: Int,
        pageWidth: Int,
        flingDistance: Int,
        minimumVelocity: Int,
    ): Int {
        val initialVelocityIsMeaningful = abs(initialVelocity) > minimumVelocity
        val currentVelocityIsMeaningful = abs(currentVelocity) > minimumVelocity
        val isFling = abs(deltaX) > flingDistance && currentVelocityIsMeaningful
        if (isFling) {
            val reversedDirection = initialVelocityIsMeaningful &&
                ((initialVelocity < 0 && currentVelocity > 0) || (initialVelocity > 0 && currentVelocity < 0))
            if (!reversedDirection) {
                return if (currentVelocity >= 0) currentPage - 1 else currentPage + 1
            }
        }

        if (pageWidth <= 0) {
            return currentPage
        }
        val dragThreshold = (pageWidth * PAGE_DRAG_TURN_THRESHOLD_FRACTION).roundToInt().coerceAtLeast(1)
        return when {
            deltaX <= -dragThreshold -> currentPage + 1
            deltaX >= dragThreshold -> currentPage - 1
            else -> currentPage
        }
    }
}
