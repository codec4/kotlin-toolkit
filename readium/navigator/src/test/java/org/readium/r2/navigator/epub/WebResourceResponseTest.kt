package org.readium.r2.navigator.epub

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebResourceResponseTest {
    @Test
    fun allowCorsCopiesReadOnlyResponseHeaders() {
        val response = WebResourceResponse(
            "text/css",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0)),
        ).apply {
            responseHeaders = Collections.unmodifiableMap(
                mapOf("Cache-Control" to "max-age=3600"),
            )
        }

        response.allowCors()

        assertEquals("max-age=3600", response.responseHeaders["Cache-Control"])
        assertEquals("*", response.responseHeaders["Access-Control-Allow-Origin"])
    }
}
