@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.r2.shared.util.resource.content

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.checkSuccess
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.StringResource
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResourceContentExtractorTest {

    @Test
    fun `xhtml extraction preserves content after a self-closing title`() = runTest {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <title/>
                </head>
                <body>
                    <p>Before apology.&#x20;After.</p>
                </body>
            </html>
        """.trimIndent()
        val resource = StringResource(xhtml)
        val extractor = DefaultResourceContentExtractorFactory()
            .createExtractor(resource, MediaType.XHTML)!!

        val text = extractor.extractText(resource).checkSuccess()

        assertEquals("Before apology. After.", text)
    }

    @Test
    fun `html extraction remains tolerant of html markup`() = runTest {
        val html = """
            <html>
                <head><title>Example</title></head>
                <body><p>Before<br>After</p></body>
            </html>
        """.trimIndent()
        val resource = StringResource(html)
        val extractor = DefaultResourceContentExtractorFactory()
            .createExtractor(resource, MediaType.HTML)!!

        val text = extractor.extractText(resource).checkSuccess()

        assertEquals("Before After", text)
    }
}
