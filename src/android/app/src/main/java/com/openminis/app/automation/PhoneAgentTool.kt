package com.openminis.app.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * PhoneAgentTool exposes the `phone_screen_action` tool to the agent.
 * Enables autonomous screen vision, touch actions, app navigation, and silent background control.
 */
object PhoneAgentTool {
    const val NAME = "phone_screen_action"
    private const val TAG = "PhoneAgentTool"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Control Android device and third-party apps via screen vision, touch gestures, and system actions. " +
            "Supports both foreground interaction and silent background execution (Shower VirtualDisplay). " +
            "Actions: 'tap', 'swipe', 'type', 'key', 'open_app', 'screenshot', 'dump_elements', 'wait'. " +
            "After actions like tap/swipe/open_app, an updated screenshot is automatically returned so you can visually verify the result. " +
            "For coordinates read from that image, set coordinate_space='screenshot' and copy its screenshot_ref. " +
            "Alternatively use coordinate_space='normalized' with x/y in [0,1] and the same screenshot_ref. " +
            "Use the latest screenshot after every UI-changing action; references expire after 300 seconds and rotation/display changes invalidate them. " +
            "Omitting coordinate_space preserves legacy device screen pixels, which can differ from the resized image pixels.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise 5-10 word summary of what this action does (e.g. 'Tap search bar', 'Swipe down on news feed')."),
            "action" to AgentToolParam("string", "The action to execute: 'tap', 'swipe', 'type', 'key', 'open_app', 'screenshot', 'dump_elements', 'wait'."),
            "coordinate_space" to AgentToolParam("string", "Coordinate space for tap/swipe: device (default, integer screen pixels), screenshot (returned image pixels), or normalized (0..1). screenshot/normalized require screenshot_ref.", enumValues = listOf("device", "screenshot", "normalized")),
            "screenshot_ref" to AgentToolParam("string", "Copy this opaque reference exactly from the latest screenshot result on the target display. Required for screenshot/normalized coordinates; also validates device coordinates when supplied. Never construct it yourself."),
            "x" to AgentToolParam("number", "Starting X for tap/swipe in the selected coordinate_space. device uses integer pixels; normalized uses 0..1."),
            "y" to AgentToolParam("number", "Starting Y for tap/swipe in the selected coordinate_space. device uses integer pixels; normalized uses 0..1."),
            "x2" to AgentToolParam("number", "Target X for swipe in the same coordinate_space."),
            "y2" to AgentToolParam("number", "Target Y for swipe in the same coordinate_space."),
            "text" to AgentToolParam("string", "Text string to type into currently focused element (required for 'type')"),
            "key_code" to AgentToolParam("string", "Hardware or system key: 'BACK', 'HOME', 'RECENTS', 'ENTER', 'VOLUME_UP', 'VOLUME_DOWN' (required for 'key')"),
            "target_package" to AgentToolParam("string", "Android package name to launch, e.g. 'com.tencent.mm' or 'com.android.settings' (required for 'open_app')"),
            "silent_background" to AgentToolParam("boolean", "If true, attempts action execution on a secondary/virtual display in the background (requires an active secondary/virtual display or external display via Shizuku). If unavailable, falls back to default display with notice."),
            "display_id" to AgentToolParam("integer", "Target Android display ID (default 0; ignored when silent_background is true). Must match the display_id in screenshot_ref."),
            "wait_ms" to AgentToolParam("integer", "Delay in milliseconds before/during action (default: 500 for gestures, or specified time for 'wait')."),
            "capture_screenshot" to AgentToolParam("boolean", "Whether to return the latest post-action screenshot for visual perception (default: true)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "coordinate_space", "screenshot_ref", "x", "y", "x2", "y2", "text", "key_code",
            "target_package", "silent_background", "display_id", "wait_ms", "capture_screenshot",
        ),
    )

    suspend fun execute(
        argsJson: String,
        context: Context,
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            val action = args.optString("action", "").lowercase().trim()
            val silentBackground = args.optBoolean("silent_background", false)
            val waitMs = args.optLong("wait_ms", 500L)
            val shouldCaptureScreenshot = args.optBoolean("capture_screenshot", true)

            // Resolve target displayId
            val displayId = if (silentBackground) {
                ShowerDisplayManager.getOrAllocateDisplayId(context)
            } else {
                args.optInt("display_id", 0)
            }
            require(displayId >= 0) { "display_id must be non-negative" }

            AppLogger.info(TAG, "Executing action=$action displayId=$displayId silent=$silentBackground")

            var actionSuccess = true
            var actionMessage = ""
            var dumpedElements: List<DeviceActionDispatcher.ScreenElement>? = null
            var coordinateSpace: PhoneCoordinateSpace? = null
            var coordinateBoundsChecked = false
            var dispatchedCoordinates: List<PhoneCoordinateMapper.Point>? = null
            var requireTargetDisplay = false

            fun actionPoint(xKey: String, yKey: String): PhoneCoordinateMapper.Point {
                val space = PhoneCoordinateSpace.parse(
                    if (args.has("coordinate_space")) args.getString("coordinate_space") else null,
                )
                val reference = if (args.has("screenshot_ref")) {
                    PhoneScreenshotReference.decode(args.getString("screenshot_ref"))
                } else null
                // The existing accessibility gesture dispatcher targets only the
                // default display. Do not silently apply new image-based calls
                // for a secondary display to the foreground phone screen.
                requireTargetDisplay = displayId > 0 && (space != PhoneCoordinateSpace.DEVICE || reference != null)
                if (requireTargetDisplay) {
                    require(DeviceActionDispatcher.activeChannel() == DeviceActionDispatcher.Channel.SHIZUKU &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "Referenced coordinates on a secondary display require Shizuku on Android 10 or later"
                    }
                }
                val geometry = readDisplayGeometry(context, displayId)
                coordinateSpace = space
                coordinateBoundsChecked = geometry != null
                return PhoneCoordinateMapper.mapPoint(
                    args.getDouble(xKey), args.getDouble(yKey), space, geometry,
                    reference, SystemClock.elapsedRealtime(),
                )
            }

            when (action) {
                "tap" -> {
                    if (!args.has("x") || !args.has("y")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'tap' requires both 'x' and 'y' coordinates.",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val point = actionPoint("x", "y")
                    dispatchedCoordinates = listOf(point)
                    val res = DeviceActionDispatcher.tap(point.x, point.y, displayId, context, requireTargetDisplay = requireTargetDisplay)
                    actionSuccess = res.success
                    actionMessage = res.message
                    if (waitMs > 0) delay(waitMs)
                }
                "swipe" -> {
                    if (!args.has("x") || !args.has("y") || !args.has("x2") || !args.has("y2")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'swipe' requires 'x', 'y', 'x2', and 'y2' coordinates.",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val start = actionPoint("x", "y")
                    val end = actionPoint("x2", "y2")
                    dispatchedCoordinates = listOf(start, end)
                    val res = DeviceActionDispatcher.swipe(start.x, start.y, end.x, end.y, durationMs = waitMs.coerceAtLeast(200L), displayId = displayId, context = context, requireTargetDisplay = requireTargetDisplay)
                    actionSuccess = res.success
                    actionMessage = res.message
                    delay(300L)
                }
                "type" -> {
                    if (!args.has("text")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'type' requires 'text' parameter.",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val text = args.getString("text")
                    val res = DeviceActionDispatcher.typeText(text, displayId, context)
                    actionSuccess = res.success
                    actionMessage = res.message
                    delay(300L)
                }
                "key" -> {
                    if (!args.has("key_code")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'key' requires 'key_code' parameter (e.g. 'BACK', 'HOME', 'ENTER').",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val key = args.getString("key_code")
                    val res = DeviceActionDispatcher.pressKey(key, displayId, context)
                    actionSuccess = res.success
                    actionMessage = res.message
                    delay(300L)
                }
                "open_app" -> {
                    if (!args.has("target_package")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'open_app' requires 'target_package' parameter (e.g. 'com.android.settings').",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val pkg = args.getString("target_package")
                    val res = if (silentBackground && displayId > 0) {
                        val ok = ShowerDisplayManager.launchOnDisplay(pkg, displayId, context)
                        DeviceActionDispatcher.ActionResult(ok, DeviceActionDispatcher.Channel.SHIZUKU, if (ok) "Launched $pkg on VirtualDisplay $displayId" else "Failed to launch $pkg on VirtualDisplay $displayId")
                    } else {
                        DeviceActionDispatcher.openApp(pkg, displayId, context)
                    }
                    actionSuccess = res.success
                    actionMessage = res.message
                    delay(1200L) // Wait for app launch animation
                }
                "wait" -> {
                    delay(waitMs.coerceAtLeast(100L))
                    actionSuccess = true
                    actionMessage = "Waited ${waitMs}ms"
                }
                "dump_elements" -> {
                    dumpedElements = DeviceActionDispatcher.dumpScreenElements(displayId)
                    actionSuccess = true
                    actionMessage = "Dumped ${dumpedElements.size} UI elements"
                }
                "screenshot" -> {
                    actionSuccess = true
                    actionMessage = "Captured screen"
                }
                else -> {
                    return ToolExecutionResult(
                        output = "Error: Unsupported action '$action'. Must be one of: tap, swipe, type, key, open_app, screenshot, dump_elements, wait",
                        success = false,
                        toolTitle = toolTitle,
                    )
                }
            }

            // Post-action screenshot acquisition
            var screenshotBytes: ByteArray? = null
            var screenshotFilePath: String? = null
            var screenDimensions = ""
            var screenshotMetadata: JSONObject? = null

            if (shouldCaptureScreenshot || action == "screenshot") {
                val geometryBefore = readDisplayGeometry(context, displayId)
                val capturedAtElapsedMs = SystemClock.elapsedRealtime()
                val (bitmap, shotMsg) = DeviceActionDispatcher.captureScreenshot(displayId, context)
                val geometryAfter = readDisplayGeometry(context, displayId)
                if (bitmap != null) {
                    var scaled: Bitmap? = null
                    try {
                        val maxEdge = 1280
                        scaled = if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
                            val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
                            val w = (bitmap.width * scale).toInt()
                            val h = (bitmap.height * scale).toInt()
                            Bitmap.createScaledBitmap(bitmap, w, h, true)
                        } else bitmap

                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        screenshotBytes = bos.toByteArray()
                        screenDimensions = "${scaled.width}x${scaled.height}"
                        screenshotMetadata = JSONObject().apply {
                            put("image_width", scaled.width)
                            put("image_height", scaled.height)
                            put("original_image_width", bitmap.width)
                            put("original_image_height", bitmap.height)
                            put("image_coordinate_space", "screenshot")
                            put("device_coordinate_space", "device")
                            put("coordinate_origin", "top-left")
                            put("display_id", displayId)
                            put("screen_width", geometryAfter?.width ?: JSONObject.NULL)
                            put("screen_height", geometryAfter?.height ?: JSONObject.NULL)
                            put("rotation_degrees", geometryAfter?.rotation?.times(90) ?: JSONObject.NULL)
                            // Do not mistake an unrelated/cropped/native-orientation capture
                            // for input geometry merely because DisplayManager is available.
                            if (geometryAfter != null && geometryBefore == geometryAfter &&
                                bitmap.width == geometryAfter.width && bitmap.height == geometryAfter.height) {
                                val reference = PhoneScreenshotReference(
                                    geometryAfter, scaled.width, scaled.height, capturedAtElapsedMs,
                                )
                                put("screenshot_ref", reference.encode())
                                put("reference_valid_for_ms", PhoneCoordinateMapper.SCREENSHOT_MAX_AGE_MS)
                                put("coordinate_notice", "Use coordinate_space=screenshot for image pixels or normalized for 0..1, with this screenshot_ref. Use the latest image after each UI change.")
                            } else {
                                put("coordinate_notice", "Screen geometry unavailable, changed during capture, or differs from the captured bitmap; screenshot/normalized actions are unavailable. Capture a fresh screenshot. Device pixel semantics remain unchanged.")
                            }
                        }

                        // Write to cache file for UI thumbnail/detail view
                        try {
                            val cacheFile = File(context.cacheDir, "phone_screen_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(cacheFile).use { fos -> fos.write(screenshotBytes) }
                            screenshotFilePath = cacheFile.absolutePath
                        } catch (_: Throwable) {}
                    } finally {
                        if (scaled != null && scaled !== bitmap) scaled.recycle()
                        bitmap.recycle()
                    }
                } else {
                    actionMessage += " (Screenshot capture notice: $shotMsg)"
                }
            }

            // Construct structured JSON output
            val outputJson = JSONObject().apply {
                put("action", action)
                put("success", actionSuccess)
                put("message", actionMessage)
                put("channel", DeviceActionDispatcher.activeChannel().name.lowercase())
                put("display_id", displayId)
                put("silent_background", silentBackground)
                if (silentBackground && displayId == 0) {
                    put("silent_background_notice", "No secondary or virtual display detected; fell back to default display (id=0).")
                }
                if (screenDimensions.isNotEmpty()) {
                    // Retain this legacy image-size field; the explicit metadata separates it from screen size.
                    put("screen_resolution", screenDimensions)
                }
                screenshotMetadata?.let { put("screenshot", it) }
                coordinateSpace?.let {
                    put("coordinate_space", it.value)
                    put("coordinate_bounds_checked", coordinateBoundsChecked)
                    if (!coordinateBoundsChecked) {
                        put("coordinate_notice", "Display geometry unavailable: legacy device pixels used without upper-bound validation.")
                    }
                }
                dispatchedCoordinates?.let { points ->
                    put("dispatched_device_coordinates", JSONArray().apply {
                        points.forEach { point -> put(JSONObject().put("x", point.x).put("y", point.y)) }
                    })
                }
                if (dumpedElements != null) {
                    val elemArray = JSONArray()
                    dumpedElements.take(50).forEach { elem ->
                        elemArray.put(JSONObject().apply {
                            elem.text?.let { put("text", it) }
                            elem.description?.let { put("desc", it) }
                            elem.id?.let { put("id", it) }
                            put("bounds", "[${elem.bounds.left},${elem.bounds.top},${elem.bounds.right},${elem.bounds.bottom}]")
                            if (elem.clickable) put("clickable", true)
                            if (elem.editable) put("editable", true)
                        })
                    }
                    put("elements", elemArray)
                }
            }

            ToolExecutionResult(
                output = outputJson.toString(2),
                success = actionSuccess,
                imageData = screenshotBytes,
                imageMimeType = if (screenshotBytes != null) "image/jpeg" else null,
                toolTitle = toolTitle,
                imageFilePath = screenshotFilePath,
            )
        } catch (e: Exception) {
            AppLogger.error(TAG, "PhoneAgentTool execution failed", e)
            ToolExecutionResult(
                output = "Error executing phone_screen_action: ${e.message}",
                success = false,
                toolTitle = "Phone Action Failed",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readDisplayGeometry(context: Context, displayId: Int): PhoneDisplayGeometry? = try {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = manager?.getDisplay(displayId)
        if (display == null || !display.isValid) null else {
            val size = Point()
            display.getRealSize(size)
            PhoneDisplayGeometry(displayId, size.x, size.y, display.rotation)
        }
    } catch (_: Exception) {
        null
    }
}
