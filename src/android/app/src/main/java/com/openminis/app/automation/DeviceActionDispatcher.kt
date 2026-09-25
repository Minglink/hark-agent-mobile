package com.openminis.app.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Unified dual-channel device automation dispatcher.
 * Intelligently routes screen actions to Shizuku (ADB privileged channel)
 * or MinisAccessibilityService (UI accessibility channel).
 */
object DeviceActionDispatcher {
    private const val TAG = "DeviceActionDispatcher"

    enum class Channel {
        SHIZUKU,
        ACCESSIBILITY,
        NONE
    }

    data class ActionResult(
        val success: Boolean,
        val channel: Channel,
        val message: String,
        val screenshot: Bitmap? = null,
        val extraData: Map<String, Any?> = emptyMap(),
    )

    data class ScreenElement(
        val id: String?,
        val text: String?,
        val description: String?,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val className: String?,
    )

    fun activeChannel(): Channel = when {
        ShizukuManager.isReady() -> Channel.SHIZUKU
        MinisAccessibilityService.getInstance() != null -> Channel.ACCESSIBILITY
        else -> Channel.NONE
    }

    suspend fun tap(
        x: Int,
        y: Int,
        displayId: Int = 0,
        context: Context? = null,
    ): ActionResult = withContext(Dispatchers.IO) {
        val channel = activeChannel()
        if (x < 0 || y < 0) {
            return@withContext ActionResult(false, channel, "Invalid coordinates ($x, $y): coordinates must be non-negative")
        }
        when (channel) {
            Channel.SHIZUKU -> {
                val displayArg = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf("-d", displayId.toString()) else emptyList()
                val cmd = (listOf("input") + displayArg + listOf("tap", x.toString(), y.toString())).toTypedArray()
                val res = ShizukuManager.runProcess(cmd)
                ActionResult(
                    success = res.exitCode == 0,
                    channel = Channel.SHIZUKU,
                    message = if (res.exitCode == 0) "Tapped ($x, $y) via Shizuku" else "Shizuku tap failed: ${res.combined}",
                )
            }
            Channel.ACCESSIBILITY -> {
                val service = MinisAccessibilityService.getInstance()
                    ?: return@withContext ActionResult(false, Channel.NONE, "AccessibilityService not running")
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val ok = service.dispatchSimpleGesture(path, startTime = 0L, durationMs = 50L)
                ActionResult(
                    success = ok,
                    channel = Channel.ACCESSIBILITY,
                    message = if (ok) "Tapped ($x, $y) via Accessibility" else "Accessibility tap gesture failed",
                )
            }
            Channel.NONE -> ActionResult(false, Channel.NONE, "No automation channel available (Neither Shizuku nor Accessibility is enabled)")
        }
    }

    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = 300L,
        displayId: Int = 0,
        context: Context? = null,
    ): ActionResult = withContext(Dispatchers.IO) {
        val channel = activeChannel()
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return@withContext ActionResult(false, channel, "Invalid coordinates ($x1,$y1)->($x2,$y2): coordinates must be non-negative")
        }
        when (channel) {
            Channel.SHIZUKU -> {
                val displayArg = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf("-d", displayId.toString()) else emptyList()
                val cmd = (listOf("input") + displayArg + listOf("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())).toTypedArray()
                val res = ShizukuManager.runProcess(cmd)
                ActionResult(
                    success = res.exitCode == 0,
                    channel = Channel.SHIZUKU,
                    message = if (res.exitCode == 0) "Swiped ($x1,$y1)->($x2,$y2) via Shizuku" else "Shizuku swipe failed: ${res.combined}",
                )
            }
            Channel.ACCESSIBILITY -> {
                val service = MinisAccessibilityService.getInstance()
                    ?: return@withContext ActionResult(false, Channel.NONE, "AccessibilityService not running")
                val path = Path().apply {
                    moveTo(x1.toFloat(), y1.toFloat())
                    lineTo(x2.toFloat(), y2.toFloat())
                }
                val ok = service.dispatchSimpleGesture(path, startTime = 0L, durationMs = durationMs)
                ActionResult(
                    success = ok,
                    channel = Channel.ACCESSIBILITY,
                    message = if (ok) "Swiped ($x1,$y1)->($x2,$y2) via Accessibility" else "Accessibility swipe gesture failed",
                )
            }
            Channel.NONE -> ActionResult(false, Channel.NONE, "No automation channel available")
        }
    }

    suspend fun typeText(
        text: String,
        displayId: Int = 0,
        context: Context? = null,
    ): ActionResult = withContext(Dispatchers.IO) {
        val channel = activeChannel()
        val isAscii = text.all { it.code in 32..126 }

        // Accessibility setNodeText supports full Unicode/Chinese directly into focused input
        val service = MinisAccessibilityService.getInstance()
        if (service != null && (!isAscii || channel == Channel.ACCESSIBILITY)) {
            val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val ok = service.setNodeText(focused, text)
                if (ok) {
                    return@withContext ActionResult(
                        success = true,
                        channel = Channel.ACCESSIBILITY,
                        message = "Typed text into focused input element via Accessibility",
                    )
                }
            }
        }

        when (channel) {
            Channel.SHIZUKU -> {
                // Escape text for input text
                val escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'")
                val cmd = arrayOf("input", "text", escaped)
                val res = ShizukuManager.runProcess(cmd)
                ActionResult(
                    success = res.exitCode == 0,
                    channel = Channel.SHIZUKU,
                    message = if (res.exitCode == 0) "Typed text via Shizuku" else "Shizuku input text failed: ${res.combined}",
                )
            }
            Channel.ACCESSIBILITY -> {
                if (service != null) {
                    val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null) {
                        val ok = service.setNodeText(focused, text)
                        ActionResult(
                            success = ok,
                            channel = Channel.ACCESSIBILITY,
                            message = if (ok) "Typed text into focused input element via Accessibility" else "Failed to set text on focused node",
                        )
                    } else {
                        ActionResult(false, Channel.ACCESSIBILITY, "No focused editable input element found via Accessibility")
                    }
                } else {
                    ActionResult(false, Channel.NONE, "AccessibilityService not running")
                }
            }
            Channel.NONE -> ActionResult(false, Channel.NONE, "No automation channel available")
        }
    }

    suspend fun pressKey(
        keyName: String,
        displayId: Int = 0,
        context: Context? = null,
    ): ActionResult = withContext(Dispatchers.IO) {
        val upperKey = keyName.uppercase().trim()
        val channel = activeChannel()

        val adbKeyCode = when (upperKey) {
            "BACK" -> 4
            "HOME" -> 3
            "RECENTS", "APP_SWITCH" -> 187
            "ENTER" -> 66
            "DEL", "DELETE", "BACKSPACE" -> 67
            "TAB" -> 61
            "VOLUME_UP" -> 24
            "VOLUME_DOWN" -> 25
            "POWER" -> 26
            else -> upperKey.toIntOrNull()
        }

        if (channel == Channel.SHIZUKU && adbKeyCode != null) {
            val displayArg = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf("-d", displayId.toString()) else emptyList()
            val cmd = (listOf("input") + displayArg + listOf("keyevent", adbKeyCode.toString())).toTypedArray()
            val res = ShizukuManager.runProcess(cmd)
            return@withContext ActionResult(
                success = res.exitCode == 0,
                channel = Channel.SHIZUKU,
                message = if (res.exitCode == 0) "Key $upperKey pressed via Shizuku" else "Shizuku keyevent failed: ${res.combined}",
            )
        }

        // Accessibility global actions fallback
        val service = MinisAccessibilityService.getInstance()
        if (service != null) {
            val globalAction = when (upperKey) {
                "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
                "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                "RECENTS", "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "LOCK_SCREEN" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else -> null
            }
            if (globalAction != null) {
                val ok = service.performGlobalAction(globalAction)
                return@withContext ActionResult(
                    success = ok,
                    channel = Channel.ACCESSIBILITY,
                    message = if (ok) "Global action $upperKey performed via Accessibility" else "Accessibility global action failed",
                )
            }
        }

        ActionResult(false, channel, "Unsupported key '$upperKey' on available channels")
    }

    suspend fun openApp(
        packageName: String,
        displayId: Int = 0,
        context: Context? = null,
    ): ActionResult = withContext(Dispatchers.IO) {
        val channel = activeChannel()
        if (channel == Channel.SHIZUKU) {
            val displayArg = if (displayId > 0) listOf("--display", displayId.toString()) else emptyList()
            val cmd = (listOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")).toTypedArray()
            val res = ShizukuManager.runProcess(cmd)
            if (res.exitCode == 0) {
                return@withContext ActionResult(true, Channel.SHIZUKU, "Launched $packageName via Shizuku monkey")
            }
        }

        // Context launch fallback
        if (context != null) {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@withContext ActionResult(true, Channel.ACCESSIBILITY, "Launched $packageName via Intent")
            }
        }

        ActionResult(false, channel, "Failed to launch $packageName (package not found or launch intent unavailable)")
    }

    suspend fun captureScreenshot(
        displayId: Int = 0,
        context: Context? = null,
    ): Pair<Bitmap?, String> = withContext(Dispatchers.IO) {
        // Channel 1: Shizuku screencap
        if (ShizukuManager.isReady()) {
            val tempFile = File.createTempFile("hark_screen_", ".png", context?.cacheDir)
            try {
                val displayArg = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf("-d", displayId.toString()) else emptyList()
                val cmd = (listOf("screencap") + displayArg + listOf("-p", tempFile.absolutePath)).toTypedArray()
                val res = ShizukuManager.runProcess(cmd, timeoutMs = 8_000L)
                if (res.exitCode == 0 && tempFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                    if (bmp != null) return@withContext bmp to "Captured screenshot via Shizuku"
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Shizuku screencap error: ${t.message}")
            } finally {
                tempFile.delete()
            }
        }

        // Channel 2: Accessibility captureScreenshot (API 30+)
        val service = MinisAccessibilityService.getInstance()
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val shotRes = service.captureScreenshot(displayId = displayId, timeoutMs = 6_000L)
                if (shotRes.bitmap != null) {
                    return@withContext shotRes.bitmap to "Captured screenshot via AccessibilityService"
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Accessibility captureScreenshot error: ${t.message}")
            }
        }

        null to "Screenshot capture failed on all available channels"
    }

    suspend fun dumpScreenElements(
        displayId: Int = 0,
    ): List<ScreenElement> = withContext(Dispatchers.Default) {
        val service = MinisAccessibilityService.getInstance() ?: return@withContext emptyList()
        val elements = mutableListOf<ScreenElement>()
        val roots = service.rootNodes()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val resId = node.viewIdResourceName?.takeIf { it.isNotBlank() }

            if (text != null || desc != null || resId != null || node.isClickable || node.isEditable) {
                elements.add(
                    ScreenElement(
                        id = resId,
                        text = text,
                        description = desc,
                        bounds = rect,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        className = node.className?.toString(),
                    )
                )
            }

            for (i in 0 until node.childCount) {
                try {
                    traverse(node.getChild(i))
                } catch (_: Throwable) {}
            }
        }

        for (root in roots) {
            try { traverse(root) } catch (_: Throwable) {}
        }

        elements
    }
}
