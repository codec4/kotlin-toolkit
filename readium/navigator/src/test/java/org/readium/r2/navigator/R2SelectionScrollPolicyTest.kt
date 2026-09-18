package org.readium.r2.navigator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class R2SelectionScrollPolicyTest {
    private val current = WebViewScrollOffset(x = 0, y = 400)

    @Test
    fun withoutSelectionEveryRequestedOffsetApplies() {
        assertEquals(
            WebViewScrollOffset(x = 1200, y = 900),
            allowed(isSelecting = false, scrollMode = false, requested = WebViewScrollOffset(1200, 900)),
        )
        assertEquals(
            WebViewScrollOffset(x = 30, y = 900),
            allowed(isSelecting = false, scrollMode = true, requested = WebViewScrollOffset(30, 900)),
        )
    }

    @Test
    fun selectedScrollModeMovesVertically() {
        assertEquals(
            WebViewScrollOffset(x = 0, y = 1300),
            allowed(isSelecting = true, scrollMode = true, requested = WebViewScrollOffset(0, 1300)),
        )
        assertEquals(
            WebViewScrollOffset(x = 0, y = 100),
            allowed(isSelecting = true, scrollMode = true, requested = WebViewScrollOffset(0, 100)),
        )
    }

    @Test
    fun selectedScrollModeKeepsHorizontalOffset() {
        assertEquals(
            WebViewScrollOffset(x = 0, y = 700),
            allowed(isSelecting = true, scrollMode = true, requested = WebViewScrollOffset(640, 700)),
        )
    }

    @Test
    fun selectedVerticalTextScrollModeMovesOnlyAlongItsScrollAxis() {
        assertEquals(
            WebViewScrollOffset(x = 640, y = 400),
            allowed(
                isSelecting = true,
                scrollMode = true,
                verticalText = true,
                requested = WebViewScrollOffset(640, 700),
            ),
        )
    }

    @Test
    fun selectedPaginatedModeSuppressesMovement() {
        assertNull(
            allowed(isSelecting = true, scrollMode = false, requested = WebViewScrollOffset(1000, 400)),
        )
        assertNull(
            allowed(isSelecting = true, scrollMode = false, requested = WebViewScrollOffset(0, 900)),
        )
    }

    private fun allowed(
        isSelecting: Boolean,
        scrollMode: Boolean,
        requested: WebViewScrollOffset,
        verticalText: Boolean = false,
    ): WebViewScrollOffset? =
        SelectionScrollPolicy.allowedOverScroll(
            isSelecting = isSelecting,
            scrollMode = scrollMode,
            verticalText = verticalText,
            current = current,
            requested = requested,
        )
}
