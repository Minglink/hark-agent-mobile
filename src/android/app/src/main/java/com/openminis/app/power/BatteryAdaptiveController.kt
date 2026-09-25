package com.openminis.app.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * BatteryAdaptiveController — 移动端电量与功耗自适应调度控制器
 *
 * 借鉴 Hermes Agent (agent/battery.py):
 * 根据 Android 设备当前电量、充电状态及省电模式动态调整 Agent 算力输出，
 * 低电量时主动限制子代理并发数与 Shell 执行超时，充电时全速释放算力。
 */
object BatteryAdaptiveController {

    data class BatteryState(
        val percentage: Int,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean
    )

    fun getBatteryState(context: Context): BatteryState {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale > 0) (level * 100 / scale) else 100

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode == true

        return BatteryState(
            percentage = batteryPct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave
        )
    }

    /**
     * 判断是否处于低电量受限状态
     */
    fun isLowBattery(context: Context): Boolean {
        val state = getBatteryState(context)
        return !state.isCharging && (state.percentage <= 20 || state.isPowerSaveMode)
    }

    /**
     * 根据电量动态决策最大允许的子代理并发数
     */
    fun resolveMaxSubagentConcurrency(context: Context, defaultMax: Int = 4): Int {
        val state = getBatteryState(context)
        return when {
            state.isCharging -> defaultMax
            state.percentage <= 20 || state.isPowerSaveMode -> 1
            state.percentage <= 40 -> 2.coerceAtMost(defaultMax)
            else -> defaultMax
        }
    }

    /**
     * 根据电量动态校准 Shell 执行超时时间（秒）
     */
    fun resolveShellTimeout(context: Context, requestedTimeout: Int): Int {
        val state = getBatteryState(context)
        if (!state.isCharging && (state.percentage <= 15 || state.isPowerSaveMode)) {
            // 低电量时压缩超时，防止死锁或长时间占用 CPU 烧干电池
            return requestedTimeout.coerceAtMost(300)
        }
        return requestedTimeout
    }
}
