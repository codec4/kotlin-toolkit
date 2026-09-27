package org.readium.r2.navigator.epub

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubLocatorTargetTest {
    private val position = Locator(
        href = Url("OEBPS/chapter8.xhtml")!!,
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = 0.4, position = 128, totalProgression = 0.52),
    )
    private val picture = position.copy(
        locations = position.locations.copy(
            otherLocations = mapOf("cssSelector" to "#fig8 > img:nth-of-type(1)", "imageHref" to "OEBPS/image/f8.jpeg"),
        ),
    )

    @Test
    fun aPassageScrollsToItsText() {
        assertTrue(position.copy(text = Locator.Text(highlight = "the cell divides")).scrollsToTarget())
    }

    @Test
    fun aLocatorThatTargetsOnlyAnElementScrollsToIt() {
        assertTrue(picture.scrollsToTarget())
    }

    @Test
    fun aPositionKeepsToItsProgressionOrItsOwnAnchor() {
        val resumed = picture.withNativeScrollSnapshot(
            EpubNativeScrollSnapshot(
                scrollX = 0,
                scrollY = 1200,
                viewportWidth = 800,
                viewportHeight = 1200,
                contentHeight = 9000,
                progression = 0.13,
                axis = "vertical",
            ),
        )

        assertFalse(position.scrollsToTarget())
        assertFalse(resumed.scrollsToTarget())
    }
}
