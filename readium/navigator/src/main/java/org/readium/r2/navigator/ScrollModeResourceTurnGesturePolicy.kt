/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator

import kotlin.math.abs

/**
 * Decides whether a horizontal drag in EPUB scroll mode should turn the resource.
 */
public fun interface ScrollModeResourceTurnGesturePolicy {

    public fun shouldTurnResource(
        deltaX: Int,
        deltaY: Int,
        flingDistance: Int,
        touchSlop: Int,
    ): Boolean

    public companion object {
        /**
         * Matches Readium's historical scroll-mode resource-turn behavior.
         */
        public val Legacy: ScrollModeResourceTurnGesturePolicy =
            ScrollModeResourceTurnGesturePolicy { _, deltaY, _, _ ->
                abs(deltaY) < 200
            }
    }
}
