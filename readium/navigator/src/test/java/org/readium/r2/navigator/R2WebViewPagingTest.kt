package org.readium.r2.navigator

import kotlin.test.Test
import kotlin.test.assertEquals

class R2WebViewPagingTest {
    @Test
    fun slowLeftDragPastThresholdTurnsToNextPage() {
        assertEquals(
            4,
            PagedWebViewGesturePolicy.targetPageForPagedDrag(
                currentPage = 3,
                initialVelocity = 0,
                currentVelocity = 0,
                deltaX = -360,
                pageWidth = 1000,
                flingDistance = 25,
                minimumVelocity = 400,
            ),
        )
    }

    @Test
    fun slowRightDragPastThresholdTurnsToPreviousPage() {
        assertEquals(
            2,
            PagedWebViewGesturePolicy.targetPageForPagedDrag(
                currentPage = 3,
                initialVelocity = 0,
                currentVelocity = 0,
                deltaX = 360,
                pageWidth = 1000,
                flingDistance = 25,
                minimumVelocity = 400,
            ),
        )
    }

    @Test
    fun shortDragKeepsCurrentPage() {
        assertEquals(
            3,
            PagedWebViewGesturePolicy.targetPageForPagedDrag(
                currentPage = 3,
                initialVelocity = 0,
                currentVelocity = 0,
                deltaX = -120,
                pageWidth = 1000,
                flingDistance = 25,
                minimumVelocity = 400,
            ),
        )
    }

    @Test
    fun shortDragSnapsBackWithoutPageTurn() {
        assertEquals(
            PagedDragSettleAction.SnapToCurrentPage,
            PagedWebViewGesturePolicy.settleAction(gesture(deltaX = -120)),
        )
    }

    @Test
    fun samePageTapSettleIsIgnored() {
        assertEquals(
            PagedDragSettleAction.Ignore,
            PagedWebViewGesturePolicy.settleAction(
                gesture(deltaX = 0, deltaY = 0, end = PagedDragEnd.TapRelease)
            ),
        )
    }

    @Test
    fun samePageMovedTapReleaseSnapsInsteadOfFallingThroughToLinkClick() {
        assertEquals(
            PagedDragSettleAction.SnapToCurrentPage,
            PagedWebViewGesturePolicy.settleAction(
                gesture(deltaX = -24, touchSlop = 10, end = PagedDragEnd.TapRelease)
            ),
        )
    }

    @Test
    fun verticalMovedTapReleaseSnapsInsteadOfFallingThroughToLinkClick() {
        assertEquals(
            PagedDragSettleAction.SnapToCurrentPage,
            PagedWebViewGesturePolicy.settleAction(
                gesture(deltaY = 24, touchSlop = 10, end = PagedDragEnd.TapRelease)
            ),
        )
    }

    @Test
    fun pageChangeStillAnimatesToTargetPage() {
        assertEquals(
            PagedDragSettleAction.Page(4),
            PagedWebViewGesturePolicy.settleAction(gesture(deltaX = -360)),
        )
    }

    @Test
    fun canceledDragSnapsBackWithoutPageTurnEvenPastThreshold() {
        assertEquals(
            PagedDragSettleAction.SnapToCurrentPage,
            PagedWebViewGesturePolicy.settleAction(
                gesture(deltaX = -360, end = PagedDragEnd.Cancel)
            ),
        )
    }

    @Test
    fun flingStillTurnsEvenBeforeDistanceThreshold() {
        assertEquals(
            4,
            PagedWebViewGesturePolicy.targetPageForPagedDrag(
                currentPage = 3,
                initialVelocity = -500,
                currentVelocity = -900,
                deltaX = -80,
                pageWidth = 1000,
                flingDistance = 25,
                minimumVelocity = 400,
            ),
        )
    }

    @Test
    fun reversedFlingFallsBackToDragDistance() {
        assertEquals(
            3,
            PagedWebViewGesturePolicy.targetPageForPagedDrag(
                currentPage = 3,
                initialVelocity = 700,
                currentVelocity = -900,
                deltaX = -80,
                pageWidth = 1000,
                flingDistance = 25,
                minimumVelocity = 400,
            ),
        )
    }

    private fun gesture(
        currentPage: Int = 3,
        pageCount: Int = 8,
        initialVelocity: Int = 0,
        currentVelocity: Int = 0,
        deltaX: Int = 0,
        deltaY: Int = 0,
        pageWidth: Int = 1000,
        flingDistance: Int = 25,
        minimumVelocity: Int = 400,
        touchSlop: Int = 10,
        end: PagedDragEnd = PagedDragEnd.DragRelease,
    ): PagedDragGesture =
        PagedDragGesture(
            currentPage = currentPage,
            pageCount = pageCount,
            initialVelocity = initialVelocity,
            currentVelocity = currentVelocity,
            deltaX = deltaX,
            deltaY = deltaY,
            pageWidth = pageWidth,
            flingDistance = flingDistance,
            minimumVelocity = minimumVelocity,
            touchSlop = touchSlop,
            end = end,
        )
}
