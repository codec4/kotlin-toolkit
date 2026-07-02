/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

private const val NATIVE_SCROLL_LOCATION_KEY = "quoteNoteNativeScroll"
private const val VIEWPORT_ANCHOR_LOCATION_KEY = "quoteNoteViewportAnchor"
private const val CSS_SELECTOR_LOCATION_KEY = "cssSelector"

public data class EpubNativeScrollSnapshot(
    val scrollX: Int,
    val scrollY: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val contentHeight: Int?,
    val progression: Double?,
    val axis: String,
) {
    public fun toLocationValue(): Map<String, Any> = buildMap {
        put("scrollX", scrollX)
        put("scrollY", scrollY)
        put("viewportWidth", viewportWidth)
        put("viewportHeight", viewportHeight)
        put("axis", axis)
        contentHeight?.let { put("contentHeight", it) }
        progression?.let { put("progression", it) }
    }

    public companion object {
        public fun fromLocator(locator: Locator): EpubNativeScrollSnapshot? =
            locator.locations.otherLocations[NATIVE_SCROLL_LOCATION_KEY]
                ?.let(::locationValueObject)
                ?.let(::fromJson)

        public fun fromLocatorJson(locatorJson: String?): EpubNativeScrollSnapshot? {
            locatorJson ?: return null
            return runCatching {
                JSONObject(locatorJson)
                    .optJSONObject("locations")
                    ?.optJSONObject(NATIVE_SCROLL_LOCATION_KEY)
                    ?.let(::fromJson)
            }.getOrNull()
        }

        private fun fromJson(json: JSONObject): EpubNativeScrollSnapshot? {
            val scrollX = json.optIntOrNull("scrollX") ?: return null
            val scrollY = json.optIntOrNull("scrollY") ?: return null
            val viewportWidth = json.optIntOrNull("viewportWidth") ?: return null
            val viewportHeight = json.optIntOrNull("viewportHeight") ?: return null
            val progression = json.optDoubleOrNull("progression")
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0.0, 1.0)
            return EpubNativeScrollSnapshot(
                scrollX = scrollX,
                scrollY = scrollY,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                contentHeight = json.optIntOrNull("contentHeight"),
                progression = progression,
                axis = json.optString("axis").takeIf { it.isNotBlank() } ?: "vertical",
            )
        }
    }
}

public data class EpubViewportAnchor(
    val cssSelector: String,
    val elementTop: Double,
    val elementLeft: Double,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val scrollX: Int,
    val scrollY: Int,
    val axis: String,
) {
    public fun toLocationValue(): Map<String, Any> = buildMap {
        put("cssSelector", cssSelector)
        put("elementTop", elementTop)
        put("elementLeft", elementLeft)
        put("viewportWidth", viewportWidth)
        put("viewportHeight", viewportHeight)
        put("scrollX", scrollX)
        put("scrollY", scrollY)
        put("axis", axis)
    }

    public companion object {
        public fun fromLocator(locator: Locator): EpubViewportAnchor? =
            locator.locations.otherLocations[VIEWPORT_ANCHOR_LOCATION_KEY]
                ?.let(::locationValueObject)
                ?.let(::fromJson)

        public fun fromLocatorJson(locatorJson: String?): EpubViewportAnchor? {
            locatorJson ?: return null
            return runCatching {
                JSONObject(locatorJson)
                    .optJSONObject("locations")
                    ?.optJSONObject(VIEWPORT_ANCHOR_LOCATION_KEY)
                    ?.let(::fromJson)
            }.getOrNull()
        }

        public fun fromCaptureJson(cssSelector: String, json: JSONObject): EpubViewportAnchor? {
            val selector = json.optString("cssSelector")
                .takeIf { it.isNotBlank() }
                ?: cssSelector.takeIf { it.isNotBlank() }
                ?: return null
            return EpubViewportAnchor(
                cssSelector = selector,
                elementTop = json.optDoubleOrNull("elementTop") ?: return null,
                elementLeft = json.optDoubleOrNull("elementLeft") ?: return null,
                viewportWidth = json.optIntOrNull("viewportWidth") ?: return null,
                viewportHeight = json.optIntOrNull("viewportHeight") ?: return null,
                scrollX = json.optIntOrNull("scrollX") ?: return null,
                scrollY = json.optIntOrNull("scrollY") ?: return null,
                axis = json.optString("axis").takeIf { it.isNotBlank() } ?: "vertical",
            )
        }

        private fun fromJson(json: JSONObject): EpubViewportAnchor? {
            val cssSelector = json.optString("cssSelector").takeIf { it.isNotBlank() } ?: return null
            return EpubViewportAnchor(
                cssSelector = cssSelector,
                elementTop = json.optDoubleOrNull("elementTop") ?: return null,
                elementLeft = json.optDoubleOrNull("elementLeft") ?: return null,
                viewportWidth = json.optIntOrNull("viewportWidth") ?: return null,
                viewportHeight = json.optIntOrNull("viewportHeight") ?: return null,
                scrollX = json.optIntOrNull("scrollX") ?: return null,
                scrollY = json.optIntOrNull("scrollY") ?: return null,
                axis = json.optString("axis").takeIf { it.isNotBlank() } ?: "vertical",
            )
        }
    }
}

public data class EpubNativeScrollRestoreResult(
    val contentHeight: Int?,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val targetScrollY: Int,
    val scrollYBeforeApply: Int,
    val actualScrollY: Int,
) {
    public fun targetReached(tolerancePx: Int): Boolean =
        abs(actualScrollY - targetScrollY) <= tolerancePx

    public fun targetWasStable(tolerancePx: Int): Boolean =
        abs(scrollYBeforeApply - targetScrollY) <= tolerancePx &&
            abs(actualScrollY - targetScrollY) <= tolerancePx
}

public data class EpubViewportAnchorRestoreResult(
    val applied: Boolean,
    val reason: String?,
    val beforeTop: Double?,
    val afterTop: Double?,
    val targetTop: Double,
    val beforeScrollY: Int?,
    val afterScrollY: Int?,
) {
    public fun targetReached(tolerancePx: Int): Boolean =
        applied && afterTop != null && abs(afterTop - targetTop) <= tolerancePx
}

public data class EpubPrecisePositionRestoreOptions(
    val retryDelayMs: Long = 120L,
    val maxWaitAttempts: Int = 8,
    val settleRetryDelayMs: Long = 160L,
    val settleMaxAttempts: Int = 30,
    val stableContentAttempts: Int = 3,
    val stableScrollAttempts: Int = 3,
    val heightTolerancePx: Int = 2,
    val scrollTolerancePx: Int = 4,
    val viewportTolerancePx: Int = 4,
)

public data class EpubPrecisePositionRestoreResult(
    val reason: String,
    val attempts: Int,
    val nativeScrollResult: EpubNativeScrollRestoreResult? = null,
    val viewportAnchorResult: EpubViewportAnchorRestoreResult? = null,
    val savedLayoutReady: Boolean = false,
    val stableContentHeightAttempts: Int = 0,
    val stableScrollAttempts: Int = 0,
) {
    public fun targetReached(options: EpubPrecisePositionRestoreOptions): Boolean =
        viewportAnchorResult?.targetReached(options.scrollTolerancePx)
            ?: nativeScrollResult?.targetReached(options.scrollTolerancePx)
            ?: false
}

public fun Locator.hasEpubPrecisePosition(): Boolean =
    EpubViewportAnchor.fromLocator(this) != null ||
        EpubNativeScrollSnapshot.fromLocator(this) != null

public fun EpubNativeScrollSnapshot.isAtResourceStart(tolerancePx: Int = 1): Boolean =
    scrollX <= tolerancePx && scrollY <= tolerancePx

public fun Locator.withoutEpubPrecisePositionAtResourceStart(): Locator {
    val locator = withoutEpubPrecisePosition()
    return locator.copy(
        locations = locator.locations.copy(
            fragments = emptyList(),
            progression = 0.0,
        ),
        text = Locator.Text(),
    )
}

public fun Locator.withoutEpubPrecisePosition(): Locator {
    val otherLocations = locations.otherLocations.toMutableMap()
    otherLocations.remove(NATIVE_SCROLL_LOCATION_KEY)
    otherLocations.remove(VIEWPORT_ANCHOR_LOCATION_KEY)
    otherLocations.remove(CSS_SELECTOR_LOCATION_KEY)
    return copy(
        locations = locations.copy(
            otherLocations = otherLocations,
        ),
    )
}

internal fun epubNativeScrollSnapshotProgression(
    scrollMode: Boolean,
    scrollY: Int,
    contentHeight: Int?,
    webViewProgression: Double,
    axis: String,
): Double? {
    val fallback = webViewProgression
        .takeIf { it.isFinite() }
        ?.coerceIn(0.0, 1.0)
    if (!scrollMode) {
        return fallback
    }
    if (!axis.equals("vertical", ignoreCase = true)) {
        return fallback
    }
    return contentHeight
        ?.takeIf { it > 0 }
        ?.let { (scrollY.toDouble() / it).coerceIn(0.0, 1.0) }
        ?: fallback
}

public fun Locator.withNativeScrollSnapshot(snapshot: EpubNativeScrollSnapshot): Locator {
    val otherLocations = locations.otherLocations.toMutableMap()
    otherLocations[NATIVE_SCROLL_LOCATION_KEY] = snapshot.toLocationValue()
    return copy(
        locations = locations.copy(
            progression = snapshot.progression ?: locations.progression,
            otherLocations = otherLocations,
        ),
    )
}

public fun Locator.withViewportAnchor(
    anchorLocator: Locator,
    anchor: EpubViewportAnchor,
): Locator {
    val otherLocations = locations.otherLocations.toMutableMap()
    otherLocations.putAll(anchorLocator.locations.otherLocations)
    otherLocations[CSS_SELECTOR_LOCATION_KEY] = anchor.cssSelector
    otherLocations[VIEWPORT_ANCHOR_LOCATION_KEY] = anchor.toLocationValue()
    return copy(
        locations = locations.copy(
            fragments = anchorLocator.locations.fragments.takeIf { it.isNotEmpty() } ?: locations.fragments,
            otherLocations = otherLocations,
        ),
        text = anchorLocator.text,
    )
}

public val Locator.cssSelector: String?
    get() = locations.otherLocations[CSS_SELECTOR_LOCATION_KEY] as? String

public fun EpubNativeScrollSnapshot.targetScrollYForContentHeight(
    currentContentHeight: Int?,
    currentViewportWidth: Int?,
    currentViewportHeight: Int?,
    heightTolerancePx: Int,
    viewportTolerancePx: Int,
): Int {
    val savedScrollY = scrollY.coerceAtLeast(0)
    val currentHeight = currentContentHeight?.takeIf { it > 0 }
    val savedHeight = contentHeight?.takeIf { it > 0 }
    val verticalProgression = progression
        ?.takeIf { axis.equals("vertical", ignoreCase = true) }
    val viewportMatches = viewportMatches(
        currentWidth = currentViewportWidth,
        currentHeight = currentViewportHeight,
        tolerancePx = viewportTolerancePx,
    )

    if (viewportMatches) {
        return savedScrollY
    }

    if (
        currentHeight != null &&
        verticalProgression != null &&
        (savedHeight == null || abs(currentHeight - savedHeight) > heightTolerancePx)
    ) {
        return (currentHeight * verticalProgression).roundToInt().coerceAtLeast(0)
    }

    return savedScrollY
}

public fun EpubNativeScrollSnapshot.savedLayoutIsReadyForRestore(
    currentContentHeight: Int?,
    currentViewportWidth: Int?,
    currentViewportHeight: Int?,
    heightTolerancePx: Int,
    viewportTolerancePx: Int,
): Boolean {
    val savedHeight = contentHeight?.takeIf { it > 0 } ?: return true
    if (!viewportMatches(
            currentWidth = currentViewportWidth,
            currentHeight = currentViewportHeight,
            tolerancePx = viewportTolerancePx,
        )
    ) {
        return true
    }
    val currentHeight = currentContentHeight?.takeIf { it > 0 } ?: return false
    return abs(currentHeight - savedHeight) <= heightTolerancePx
}

private fun EpubNativeScrollSnapshot.viewportMatches(
    currentWidth: Int?,
    currentHeight: Int?,
    tolerancePx: Int,
): Boolean {
    val savedWidth = viewportWidth.takeIf { it > 0 } ?: return false
    val savedHeight = viewportHeight.takeIf { it > 0 } ?: return false
    val width = currentWidth?.takeIf { it > 0 } ?: return false
    val height = currentHeight?.takeIf { it > 0 } ?: return false
    return abs(width - savedWidth) <= tolerancePx &&
        abs(height - savedHeight) <= tolerancePx
}

private fun locationValueObject(value: Any): JSONObject? =
    when (value) {
        is JSONObject -> value
        is Map<*, *> -> JSONObject(value.entries.associate { (key, entryValue) ->
            key.toString() to entryValue
        })
        else -> null
    }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
