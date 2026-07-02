package org.readium.r2.navigator.epub

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubViewportPositionTest {
    @Test
    fun paginatedSnapshotUsesWebViewProgression() {
        assertEquals(
            0.42,
            epubNativeScrollSnapshotProgression(
                scrollMode = false,
                scrollY = 0,
                contentHeight = 48000,
                webViewProgression = 0.42,
                axis = "horizontal",
            ),
        )
    }

    @Test
    fun verticalScrollSnapshotUsesNativeScrollProgression() {
        assertEquals(
            0.25,
            epubNativeScrollSnapshotProgression(
                scrollMode = true,
                scrollY = 12000,
                contentHeight = 48000,
                webViewProgression = 0.42,
                axis = "vertical",
            ),
        )
    }

    @Test
    fun horizontalScrollSnapshotFallsBackToWebViewProgression() {
        assertEquals(
            0.42,
            epubNativeScrollSnapshotProgression(
                scrollMode = true,
                scrollY = 0,
                contentHeight = 48000,
                webViewProgression = 0.42,
                axis = "horizontal",
            ),
        )
    }
}
