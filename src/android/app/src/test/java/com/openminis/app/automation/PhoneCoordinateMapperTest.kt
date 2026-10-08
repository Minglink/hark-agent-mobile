package com.openminis.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneCoordinateMapperTest {
    private val portrait = PhoneDisplayGeometry(0, 1440, 3200, 0)
    private val screenshot = PhoneScreenshotReference(portrait, 576, 1280, 10_000)

    private fun map(
        x: Double,
        y: Double,
        space: PhoneCoordinateSpace = PhoneCoordinateSpace.SCREENSHOT,
        display: PhoneDisplayGeometry? = portrait,
        reference: PhoneScreenshotReference? = screenshot,
        nowMs: Long = 11_000,
    ) = PhoneCoordinateMapper.mapPoint(x, y, space, display, reference, nowMs)

    @Test
    fun `high resolution screenshot coordinates map to device pixels`() {
        assertEquals(PhoneCoordinateMapper.Point(721, 1601), map(288.0, 640.0))
        assertEquals(PhoneCoordinateMapper.Point(0, 0), map(0.0, 0.0))
        assertEquals(PhoneCoordinateMapper.Point(1439, 3199), map(575.0, 1279.0))
    }

    @Test
    fun `landscape dimensions and secondary display stay associated`() {
        val landscape = PhoneDisplayGeometry(7, 2560, 1440, 1)
        val reference = PhoneScreenshotReference(landscape, 1280, 720, 10_000)
        assertEquals(PhoneCoordinateMapper.Point(2559, 1439), map(1279.0, 719.0, display = landscape, reference = reference))
        assertThrows(IllegalArgumentException::class.java) { map(100.0, 100.0, reference = reference) }
    }

    @Test
    fun `normalized endpoints map inside the device screen`() {
        assertEquals(PhoneCoordinateMapper.Point(0, 0), map(0.0, 0.0, PhoneCoordinateSpace.NORMALIZED))
        assertEquals(PhoneCoordinateMapper.Point(1439, 3199), map(1.0, 1.0, PhoneCoordinateSpace.NORMALIZED))
        assertEquals(PhoneCoordinateMapper.Point(720, 1600), map(0.5, 0.5, PhoneCoordinateSpace.NORMALIZED))
    }

    @Test
    fun `old calls retain integer device pixel semantics without a screenshot`() {
        assertEquals(PhoneCoordinateSpace.DEVICE, PhoneCoordinateSpace.parse(null))
        assertEquals(PhoneCoordinateMapper.Point(288, 640), PhoneCoordinateMapper.mapPoint(288.0, 640.0, currentDisplay = portrait))
        assertEquals(PhoneCoordinateMapper.Point(288, 640), PhoneCoordinateMapper.mapPoint(288.0, 640.0))
    }

    @Test
    fun `legacy device pixels are not rescaled even when a screenshot is referenced`() {
        assertEquals(PhoneCoordinateMapper.Point(288, 640), map(288.0, 640.0, PhoneCoordinateSpace.DEVICE))
    }

    @Test
    fun `pixel bounds are exclusive at width and height`() {
        listOf(576.0 to 0.0, 0.0 to 1280.0, -1.0 to 0.0).forEach { (x, y) ->
            assertThrows(IllegalArgumentException::class.java) { map(x, y) }
        }
        assertThrows(IllegalArgumentException::class.java) { map(1440.0, 0.0, PhoneCoordinateSpace.DEVICE) }
        assertThrows(IllegalArgumentException::class.java) { map(0.0, 3200.0, PhoneCoordinateSpace.DEVICE) }
        assertThrows(IllegalArgumentException::class.java) { map(0.1, 0.0, PhoneCoordinateSpace.DEVICE) }
        assertThrows(IllegalArgumentException::class.java) { map(Double.MAX_VALUE, 0.0, PhoneCoordinateSpace.DEVICE) }
    }

    @Test
    fun `normalized bounds and non finite numbers are rejected`() {
        listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY).forEach { x ->
            assertThrows(IllegalArgumentException::class.java) { map(x, 0.0, PhoneCoordinateSpace.NORMALIZED) }
        }
    }

    @Test
    fun `image spaces cannot guess device geometry without a reference`() {
        assertThrows(IllegalArgumentException::class.java) { map(100.0, 100.0, reference = null) }
        assertThrows(IllegalArgumentException::class.java) { map(0.5, 0.5, PhoneCoordinateSpace.NORMALIZED, reference = null) }
        assertThrows(IllegalArgumentException::class.java) { map(100.0, 100.0, display = null) }
    }

    @Test
    fun `rotation including upside down and resizing invalidate a screenshot`() {
        assertThrows(IllegalArgumentException::class.java) { map(100.0, 100.0, display = portrait.copy(rotation = 2)) }
        assertThrows(IllegalArgumentException::class.java) { map(100.0, 100.0, display = portrait.copy(width = 1080)) }
        assertThrows(IllegalArgumentException::class.java) { map(100.0, 100.0, display = portrait.copy(displayId = 1)) }
    }

    @Test
    fun `expired and future screenshot references are rejected`() {
        val limit = screenshot.capturedAtElapsedMs + PhoneCoordinateMapper.SCREENSHOT_MAX_AGE_MS
        assertEquals(PhoneCoordinateMapper.Point(0, 0), map(0.0, 0.0, nowMs = limit))
        assertThrows(IllegalArgumentException::class.java) { map(0.0, 0.0, nowMs = limit + 1) }
        assertThrows(IllegalArgumentException::class.java) { map(0.0, 0.0, nowMs = 9_999) }
        // An explicit reference also guards device-pixel calls against stale image use.
        assertThrows(IllegalArgumentException::class.java) { map(0.0, 0.0, PhoneCoordinateSpace.DEVICE, nowMs = limit + 1) }
    }

    @Test
    fun `screenshot reference round trips without global state`() {
        assertEquals(screenshot, PhoneScreenshotReference.decode(screenshot.encode()))
        listOf("", "s0:0:1440:3200:0:576:1280:10000", "s1:0:0:3200:0:576:1280:10000", "s1:bad:1440:3200:0:576:1280:10000").forEach {
            assertThrows(IllegalArgumentException::class.java) { PhoneScreenshotReference.decode(it) }
        }
    }

    @Test
    fun `one pixel image has a defined origin mapping`() {
        val reference = screenshot.copy(imageWidth = 1, imageHeight = 1)
        assertEquals(PhoneCoordinateMapper.Point(0, 0), map(0.0, 0.0, reference = reference))
    }

    @Test
    fun `unrecognized coordinate spaces never silently use legacy pixels`() {
        assertThrows(IllegalArgumentException::class.java) { PhoneCoordinateSpace.parse("image") }
    }
}
