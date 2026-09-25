package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionEngineTest {

    @Test
    fun testContextPolicyUtilization() {
        assertEquals(0.5f, ContextPolicy.utilization(100_000, 200_000), 0.001f)
        assertEquals(0.85f, ContextPolicy.utilization(170_000, 200_000), 0.001f)
        assertEquals(1.0f, ContextPolicy.utilization(200_000, 200_000), 0.001f)
        assertEquals(0.0f, ContextPolicy.utilization(0, 200_000), 0.001f)
    }

    @Test
    fun testContextUsageStateLevelAndPercentage() {
        // Normal level (< 70%)
        val normalState = ContextUsageState.compute(usedTokens = 50_000, windowTokens = 200_000)
        assertEquals(25, normalState.percentage)
        assertEquals(0.25f, normalState.ratio, 0.001f)
        assertEquals(ContextUsageState.Level.NORMAL, normalState.level)
        assertEquals(150_000, normalState.remainingTokens)

        // Warning level (70% - 85%)
        val warningState = ContextUsageState.compute(usedTokens = 150_000, windowTokens = 200_000)
        assertEquals(75, warningState.percentage)
        assertEquals(0.75f, warningState.ratio, 0.001f)
        assertEquals(ContextUsageState.Level.WARNING, warningState.level)

        // Critical level (>= 85%)
        val criticalState = ContextUsageState.compute(usedTokens = 180_000, windowTokens = 200_000)
        assertEquals(90, criticalState.percentage)
        assertEquals(0.9f, criticalState.ratio, 0.001f)
        assertEquals(ContextUsageState.Level.CRITICAL, criticalState.level)
    }

    @Test
    fun testContextPolicyThresholds() {
        val p200k = ContextPolicy.forContextWindow(200_000)
        // compactThreshold = minOf(200_000 - 20_000 = 180_000, 200_000 * 0.85 = 170_000) = 170_000
        assertEquals(170_000, p200k.compactThreshold)
        assertEquals(160_000, p200k.offloadThreshold)
        assertEquals(140_000, p200k.offloadTarget)
        assertFalse(p200k.exhaustedOnly)

        val p128k = ContextPolicy.forContextWindow(128_000)
        // compactThreshold = minOf(128_000 - 20_000 = 108_000, 128_000 * 0.85 = 108_800) = 108_000
        assertEquals(108_000, p128k.compactThreshold)

        val p100k = ContextPolicy.forContextWindow(100_000)
        // compactThreshold = minOf(100_000 - 10_000 = 90_000, 100_000 * 0.85 = 85_000) = 85_000
        assertEquals(85_000, p100k.compactThreshold)
        assertEquals(80_000, p100k.offloadThreshold)
    }
}
