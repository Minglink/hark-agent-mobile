package com.openminis.app.automation

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ShowerDisplayManager manages virtual/secondary displays for silent background execution.
 * Allows apps to be launched and operated on an offscreen display without interrupting the foreground user.
 */
object ShowerDisplayManager {
    private const val TAG = "ShowerDisplayManager"

    @Volatile
    private var activeVirtualDisplayId: Int? = null

    fun isSupported(): Boolean {
        // Shizuku provides the shell permissions needed to control and launch onto secondary displays
        return ShizukuManager.isReady()
    }

    fun currentVirtualDisplayId(): Int? = activeVirtualDisplayId

    /**
     * Finds an existing secondary/virtual display or prepares a background display slot.
     */
    suspend fun getOrAllocateDisplayId(context: Context): Int = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displays = dm?.displays ?: emptyArray()

        // Look for existing non-default display (e.g. virtual display or external)
        for (d in displays) {
            if (d.displayId != Display.DEFAULT_DISPLAY && d.isValid) {
                activeVirtualDisplayId = d.displayId
                AppLogger.info(TAG, "Found existing secondary display: id=${d.displayId}, name=${d.name}")
                return@withContext d.displayId
            }
        }

        // If Shizuku is ready, try to probe or create a simulated secondary display via settings/cmd
        if (ShizukuManager.isReady()) {
            val probeCmd = arrayOf("cmd", "display", "get-displays")
            val probeRes = ShizukuManager.runProcess(probeCmd)
            if (probeRes.exitCode == 0) {
                // Parse display IDs if present
                val match = Regex("Display\\s+(\\d+):").findAll(probeRes.stdout)
                for (m in match) {
                    val id = m.groupValues[1].toIntOrNull()
                    if (id != null && id != Display.DEFAULT_DISPLAY) {
                        activeVirtualDisplayId = id
                        AppLogger.info(TAG, "Discovered display via Shizuku: id=$id")
                        return@withContext id
                    }
                }
            }
        }

        // Fallback to default display if no secondary display could be created/found
        Display.DEFAULT_DISPLAY
    }

    suspend fun launchOnDisplay(packageName: String, displayId: Int, context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) {
            AppLogger.warning(TAG, "Shizuku not ready for launchOnDisplay")
            return@withContext false
        }

        val displayArgs = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf("--display", displayId.toString())
        } else {
            emptyList()
        }

        val cmd = (listOf("am", "start") + displayArgs + listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageName)).toTypedArray()
        val res = ShizukuManager.runProcess(cmd)
        val success = res.exitCode == 0
        AppLogger.info(TAG, "launchOnDisplay pkg=$packageName displayId=$displayId success=$success output=${res.combined}")
        success
    }

    fun releaseDisplay(displayId: Int) {
        if (activeVirtualDisplayId == displayId) {
            activeVirtualDisplayId = null
        }
    }
}
