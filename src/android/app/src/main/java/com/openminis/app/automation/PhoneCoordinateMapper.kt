package com.openminis.app.automation

import kotlin.math.roundToInt

/** Device/input geometry, never inferred from the resized image sent to the model. */
data class PhoneDisplayGeometry(
    val displayId: Int,
    val width: Int,
    val height: Int,
    /** Android Display.rotation: 0, 1, 2 or 3. */
    val rotation: Int,
) {
    init {
        require(displayId >= 0 && width > 0 && height > 0 && rotation in 0..3) {
            "Invalid display geometry"
        }
    }
}

enum class PhoneCoordinateSpace(val value: String) {
    DEVICE("device"), SCREENSHOT("screenshot"), NORMALIZED("normalized");

    companion object {
        fun parse(value: String?): PhoneCoordinateSpace = when (value) {
            null, "device" -> DEVICE // Historical x/y semantics must stay unchanged.
            "screenshot" -> SCREENSHOT
            "normalized" -> NORMALIZED
            else -> throw IllegalArgumentException("coordinate_space must be device, screenshot, or normalized")
        }
    }
}

/** Explicit, self-contained reference: no process-wide last-screenshot state or session crosstalk. */
data class PhoneScreenshotReference(
    val display: PhoneDisplayGeometry,
    val imageWidth: Int,
    val imageHeight: Int,
    val capturedAtElapsedMs: Long,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0 && capturedAtElapsedMs >= 0) {
            "Invalid screenshot reference"
        }
    }

    fun encode(): String = listOf(
        "s1", display.displayId, display.width, display.height, display.rotation,
        imageWidth, imageHeight, capturedAtElapsedMs,
    ).joinToString(":")

    companion object {
        fun decode(value: String): PhoneScreenshotReference {
            val fields = value.split(':')
            require(fields.size == 8 && fields[0] == "s1") { "Invalid screenshot_ref; copy it from a screenshot result" }
            return try {
                PhoneScreenshotReference(
                    PhoneDisplayGeometry(fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), fields[4].toInt()),
                    fields[5].toInt(), fields[6].toInt(), fields[7].toLong(),
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid screenshot_ref; copy it from a screenshot result", e)
            }
        }
    }
}

object PhoneCoordinateMapper {
    const val SCREENSHOT_MAX_AGE_MS = 300_000L

    data class Point(val x: Int, val y: Int)

    /**
     * Pixel spaces use inclusive [0, size - 1] bounds. Normalized coordinates use
     * [0, 1], with 1 mapping to the final device pixel rather than off screen.
     * Legacy device calls need no screenshot and remain usable if geometry is unavailable.
     */
    fun mapPoint(
        x: Double,
        y: Double,
        space: PhoneCoordinateSpace = PhoneCoordinateSpace.DEVICE,
        currentDisplay: PhoneDisplayGeometry? = null,
        screenshot: PhoneScreenshotReference? = null,
        nowElapsedMs: Long = 0,
    ): Point {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite numbers" }
        if (screenshot != null) validateReference(screenshot, currentDisplay, nowElapsedMs)

        return when (space) {
            PhoneCoordinateSpace.DEVICE -> {
                require(x % 1.0 == 0.0 && y % 1.0 == 0.0 && x <= Int.MAX_VALUE && y <= Int.MAX_VALUE) {
                    "device coordinates must be integer screen pixels"
                }
                require(x >= 0 && y >= 0) { "Coordinates must be non-negative" }
                if (currentDisplay != null) {
                    require(x < currentDisplay.width && y < currentDisplay.height) {
                        "Device coordinates are outside ${currentDisplay.width}x${currentDisplay.height}"
                    }
                }
                Point(x.toInt(), y.toInt())
            }
            PhoneCoordinateSpace.SCREENSHOT, PhoneCoordinateSpace.NORMALIZED -> {
                val reference = requireNotNull(screenshot) {
                    "${space.value} coordinates require screenshot_ref from a recent screenshot; capture a screenshot first"
                }
                // validateReference above requires a live geometry match before any mapping.
                val display = reference.display
                if (space == PhoneCoordinateSpace.NORMALIZED) {
                    require(x in 0.0..1.0 && y in 0.0..1.0) { "normalized coordinates must be between 0 and 1" }
                    Point((x * (display.width - 1)).roundToInt(), (y * (display.height - 1)).roundToInt())
                } else {
                    require(x in 0.0..(reference.imageWidth - 1).toDouble() &&
                        y in 0.0..(reference.imageHeight - 1).toDouble()) {
                        "Screenshot coordinates are outside ${reference.imageWidth}x${reference.imageHeight}"
                    }
                    Point(
                        mapPixel(x, reference.imageWidth, display.width),
                        mapPixel(y, reference.imageHeight, display.height),
                    )
                }
            }
        }
    }

    private fun mapPixel(value: Double, imageSize: Int, deviceSize: Int): Int =
        if (imageSize == 1) 0 else (value * (deviceSize - 1) / (imageSize - 1)).roundToInt()

    private fun validateReference(reference: PhoneScreenshotReference, currentDisplay: PhoneDisplayGeometry?, nowElapsedMs: Long) {
        require(currentDisplay != null) { "Cannot validate screenshot_ref: display geometry unavailable; capture a fresh screenshot" }
        require(reference.display == currentDisplay) {
            "Screenshot display, dimensions, or rotation changed; capture a fresh screenshot for this display"
        }
        require(nowElapsedMs >= reference.capturedAtElapsedMs &&
            nowElapsedMs - reference.capturedAtElapsedMs <= SCREENSHOT_MAX_AGE_MS) {
            "screenshot_ref expired; capture a fresh screenshot (valid for ${SCREENSHOT_MAX_AGE_MS / 1000} seconds)"
        }
    }
}
