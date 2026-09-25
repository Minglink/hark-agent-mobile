package com.openminis.app.automation

import android.content.Context
import android.graphics.Bitmap
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
            "After actions like tap/swipe/open_app, an updated screenshot is automatically returned so you can visually verify the result.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise 5-10 word summary of what this action does (e.g. 'Tap search bar', 'Swipe down on news feed')."),
            "action" to AgentToolParam("string", "The action to execute: 'tap', 'swipe', 'type', 'key', 'open_app', 'screenshot', 'dump_elements', 'wait'."),
            "x" to AgentToolParam("integer", "X coordinate in screen pixels (required for 'tap')"),
            "y" to AgentToolParam("integer", "Y coordinate in screen pixels (required for 'tap')"),
            "x2" to AgentToolParam("integer", "Target X coordinate for 'swipe'"),
            "y2" to AgentToolParam("integer", "Target Y coordinate for 'swipe'"),
            "text" to AgentToolParam("string", "Text string to type into currently focused element (required for 'type')"),
            "key_code" to AgentToolParam("string", "Hardware or system key: 'BACK', 'HOME', 'RECENTS', 'ENTER', 'VOLUME_UP', 'VOLUME_DOWN' (required for 'key')"),
            "target_package" to AgentToolParam("string", "Android package name to launch, e.g. 'com.tencent.mm' or 'com.android.settings' (required for 'open_app')"),
            "silent_background" to AgentToolParam("boolean", "If true, attempts action execution on a secondary/virtual display in the background (requires an active secondary/virtual display or external display via Shizuku). If unavailable, falls back to default display with notice."),
            "wait_ms" to AgentToolParam("integer", "Delay in milliseconds before/during action (default: 500 for gestures, or specified time for 'wait')."),
            "capture_screenshot" to AgentToolParam("boolean", "Whether to return the latest post-action screenshot for visual perception (default: true)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "x", "y", "x2", "y2", "text", "key_code",
            "target_package", "silent_background", "wait_ms", "capture_screenshot",
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

            AppLogger.info(TAG, "Executing action=$action displayId=$displayId silent=$silentBackground")

            var actionSuccess = true
            var actionMessage = ""
            var dumpedElements: List<DeviceActionDispatcher.ScreenElement>? = null

            when (action) {
                "tap" -> {
                    if (!args.has("x") || !args.has("y")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'tap' requires both 'x' and 'y' integer coordinates.",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val x = args.getInt("x")
                    val y = args.getInt("y")
                    val res = DeviceActionDispatcher.tap(x, y, displayId, context)
                    actionSuccess = res.success
                    actionMessage = res.message
                    if (waitMs > 0) delay(waitMs)
                }
                "swipe" -> {
                    if (!args.has("x") || !args.has("y") || !args.has("x2") || !args.has("y2")) {
                        return ToolExecutionResult(
                            output = "Error: Action 'swipe' requires 'x', 'y', 'x2', and 'y2' integer coordinates.",
                            success = false,
                            toolTitle = toolTitle,
                        )
                    }
                    val x1 = args.getInt("x")
                    val y1 = args.getInt("y")
                    val x2 = args.getInt("x2")
                    val y2 = args.getInt("y2")
                    val res = DeviceActionDispatcher.swipe(x1, y1, x2, y2, durationMs = waitMs.coerceAtLeast(200L), displayId = displayId, context = context)
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

            if (shouldCaptureScreenshot || action == "screenshot") {
                val (bitmap, shotMsg) = DeviceActionDispatcher.captureScreenshot(displayId, context)
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
                    put("screen_resolution", screenDimensions)
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
}
