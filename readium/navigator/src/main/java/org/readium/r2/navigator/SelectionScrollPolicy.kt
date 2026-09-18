/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator

internal data class WebViewScrollOffset(val x: Int, val y: Int)

internal object SelectionScrollPolicy {

    /**
     * Returns the scroll offset a web view may apply while [isSelecting], or null when it must keep
     * its current offset.
     *
     * Paginated mode keeps the workaround for https://github.com/readium/kotlin-toolkit/issues/325,
     * where dragging a selection handle shifts the CSS columns. Scroll mode has no columns, so it
     * may move along its scroll axis to let a selection extend past the viewport; the cross axis
     * stays put so a selection gesture cannot slide the content sideways.
     */
    fun allowedOverScroll(
        isSelecting: Boolean,
        scrollMode: Boolean,
        verticalText: Boolean,
        current: WebViewScrollOffset,
        requested: WebViewScrollOffset,
    ): WebViewScrollOffset? = when {
        !isSelecting -> requested
        !scrollMode -> null
        verticalText -> WebViewScrollOffset(x = requested.x, y = current.y)
        else -> WebViewScrollOffset(x = current.x, y = requested.y)
    }
}
