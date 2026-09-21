package com.openminis.app.data.balance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-balance-chip] Pins every vendor payload shape end-to-end at the parser
 * layer (no network): DeepSeek native-CNY, OpenRouter/Explabs credits,
 * ZenMux PAYG + subscription quota, and the host routing table.
 */
class BalanceAdaptersTest {

    // ── Routing ───────────────────────────────────────────────────────────

    @Test
    fun `routing by host`() {
        assertEquals("deepseek", BalanceAdapters.routeFor("api.deepseek.com", "openAI"))
        assertEquals("credits", BalanceAdapters.routeFor("openrouter.ai", "openAI"))
        assertEquals("credits", BalanceAdapters.routeFor("api.experientiallabs.ai", "openAI"))
        assertEquals("zenmux", BalanceAdapters.routeFor("zenmux.ai", "openAI"))
        assertEquals("orcarouter", BalanceAdapters.routeFor("api.orcarouter.ai", "openAI"))
        assertEquals("orcarouter", BalanceAdapters.routeFor("orcarouter.ai", "openAIResponses"))
        assertEquals("generic", BalanceAdapters.routeFor("relay.example.com", "openAI"))
        assertEquals("generic", BalanceAdapters.routeFor("relay.example.com", "openAIResponses"))
        assertNull(BalanceAdapters.routeFor("api.anthropic.com", "anthropic"))
        assertNull(BalanceAdapters.routeFor("generativelanguage.googleapis.com", "gemini"))
    }

    @Test
    fun `effective key prefers override`() {
        assertEquals("mgmt", BalanceAdapters.effectiveKey("sk-inference", " mgmt "))
        assertEquals("sk-inference", BalanceAdapters.effectiveKey("sk-inference", null))
        assertEquals("sk-inference", BalanceAdapters.effectiveKey("sk-inference", "   "))
    }

    // ── DeepSeek ──────────────────────────────────────────────────────────

    @Test
    fun `parses deepseek native CNY`() {
        val json = """{"is_available":true,"balance_infos":[
            {"currency":"CNY","total_balance":"110.02","granted_balance":"10.00","topped_up_balance":"100.00"}]}"""
        val info = BalanceAdapters.parseDeepSeek(json, "DeepSeek", "i1")
        assertEquals(BalanceKind.BALANCE, info.kind)
        assertEquals("CNY", info.currency)
        assertEquals(110.02, info.remaining!!, 1e-9)
        assertEquals(10.00, info.granted!!, 1e-9)
        assertEquals(110.02, info.total!!, 1e-9)
    }

    @Test
    fun `parses deepseek USD variant`() {
        val json = """{"balance_infos":[{"currency":"USD","total_balance":"12.5"}]}"""
        val info = BalanceAdapters.parseDeepSeek(json, "D", "i")
        assertEquals("USD", info.currency)
        assertEquals(12.5, info.remaining!!, 1e-9)
    }

    // ── OpenRouter / Explabs credits (shared shape) ──────────────────────

    @Test
    fun `parses openrouter wrapped string fields`() {
        val json = """{"data":{"total_credits":"10.00","total_usage":"1.23"}}"""
        val info = BalanceAdapters.parseCredits(json, "OR", "i")
        assertEquals(8.77, info.remaining!!, 1e-9)
        assertEquals(10.00, info.total!!, 1e-9)
        assertEquals(1.23, info.used!!, 1e-9)
        assertEquals("USD", info.currency)
    }

    @Test
    fun `parses explabs root numeric fields`() {
        val json = """{"total_credits":5.0,"total_usage":1.5}"""
        val info = BalanceAdapters.parseCredits(json, "Explabs", "i")
        assertEquals(3.5, info.remaining!!, 1e-9)
    }

    @Test
    fun `credits with usage only still parses`() {
        val json = """{"data":{"total_usage":2.0}}"""
        val info = BalanceAdapters.parseCredits(json, "X", "i")
        assertNull(info.total)
        assertNull(info.remaining)
        assertEquals(2.0, info.used!!, 1e-9)
    }

    // ── ZenMux ────────────────────────────────────────────────────────────

    @Test
    fun `parses zenmux payg balance`() {
        val json = """{"success":true,"data":{"currency":"usd","total_credits":482.74,
            "top_up_credits":35.0,"bonus_credits":447.74}}"""
        val info = BalanceAdapters.parseZenMuxPayg(json, "ZenMux", "i")!!
        assertEquals(BalanceKind.BALANCE, info.kind)
        assertEquals("USD", info.currency) // lower-case normalized
        assertEquals(482.74, info.remaining!!, 1e-9)
        assertEquals(447.74, info.granted!!, 1e-9)
    }

    @Test
    fun `zenmux payg failure payload returns null to fall through to subscription`() {
        assertNull(BalanceAdapters.parseZenMuxPayg("""{"success":false}""", "Z", "i"))
        assertNull(BalanceAdapters.parseZenMuxPayg("""{"unexpected":1}""", "Z", "i"))
    }

    @Test
    fun `parses zenmux subscription flows`() {
        val json = """{"success":true,"data":{"plan":{"tier":"ultra"},
            "quota_5_hour":{"max_flows":800,"used_flows":57.2,"remaining_flows":742.8,"usage_percentage":0.0715},
            "quota_7_day":{"max_flows":6182,"used_flows":416.11,"remaining_flows":5765.89}}}"""
        val info = BalanceAdapters.parseZenMuxSubscription(json, "ZenMux", "i")!!
        assertEquals(BalanceKind.QUOTA, info.kind)
        assertEquals("FLOW", info.currency)
        assertEquals(742.8, info.remaining!!, 1e-9)
        assertEquals(800.0, info.total!!, 1e-9)
        assertTrue(info.secondaryLine!!.contains("5765.89"))
    }

    // ── OrcaRouter ────────────────────────────────────────────────────────

    @Test
    fun `parses orcarouter native paid_balance and TotalUsage`() {
        val balanceJson = """{"object":"balance","workspace_id":131296,"unit":"USD","paid_balance":19.929196}"""
        val usageJson = """{"object":"list","total_usage":7.0804}"""
        val info = BalanceAdapters.parseOrcaRouter(balanceJson, usageJson, "Orca", "i")
        assertEquals(BalanceKind.BALANCE, info.kind)
        assertEquals("USD", info.currency)
        assertEquals(19.929196, info.remaining!!, 1e-6)
        assertEquals(0.070804, info.used!!, 1e-6)
        assertEquals(20.0, info.total!!, 1e-6)
    }

    @Test
    fun `parses orcarouter native balance without usage`() {
        val balanceJson = """{"object":"balance","unit":"USD","paid_balance":19.3}"""
        val info = BalanceAdapters.parseOrcaRouter(balanceJson, null, "Orca", "i")
        assertEquals(BalanceKind.BALANCE, info.kind)
        assertEquals("USD", info.currency)
        assertEquals(19.3, info.remaining!!, 1e-6)
        assertNull(info.used)
        assertNull(info.total)
    }

    @Test
    fun `orcarouter fallback treats 100M sentinel limit as SPEND without displaying 100M`() {
        val subJson = """{"object":"billing_subscription","hard_limit_usd":100000000}"""
        val usageJson = """{"total_usage":7.0804}"""
        val info = BalanceAdapters.parseOrcaRouterFallback(subJson, usageJson, "Orca", "i")
        assertEquals(BalanceKind.SPEND, info.kind)
        assertNull(info.total)
        assertEquals(0.070804, info.used!!, 1e-6)
    }

    @Test
    fun `orcarouter fallback respects realistic limit`() {
        val subJson = """{"hard_limit_usd":50.0}"""
        val usageJson = """{"total_usage":500.0}""" // 500 cents = $5.00
        val info = BalanceAdapters.parseOrcaRouterFallback(subJson, usageJson, "Orca", "i")
        assertEquals(BalanceKind.BALANCE, info.kind)
        assertEquals(45.0, info.remaining!!, 1e-6)
        assertEquals(5.0, info.used!!, 1e-6)
        assertEquals(50.0, info.total!!, 1e-6)
    }

    // ── Formatting ────────────────────────────────────────────────────────

    @Test
    fun `formats native usd and cny`() {
        val usd = BalanceInfo("i", "L", BalanceKind.BALANCE, "USD", remaining = 12.345)
        val cny = BalanceInfo("i", "L", BalanceKind.BALANCE, "CNY", remaining = 86.5)
        assertEquals("$12.35", formatBalanceAmount(usd, DisplayCurrency.NATIVE, 7.2))
        assertEquals("¥86.50", formatBalanceAmount(cny, DisplayCurrency.NATIVE, 7.2))
    }

    @Test
    fun `cross-currency conversion marks estimate`() {
        val usd = BalanceInfo("i", "L", BalanceKind.BALANCE, "USD", remaining = 10.0)
        val cny = BalanceInfo("i", "L", BalanceKind.BALANCE, "CNY", remaining = 72.0)
        val cnySpend = BalanceInfo("i", "L", BalanceKind.SPEND, "CNY", remaining = 72.0)
        assertEquals("~¥72", formatBalanceAmount(usd, DisplayCurrency.CNY, 7.2))
        assertEquals("~$10", formatBalanceAmount(cny, DisplayCurrency.USD, 7.2))
        assertEquals("-~$10", formatBalanceAmount(cnySpend, DisplayCurrency.USD, 7.2))
    }

    @Test
    fun `spend renders negative and quota renders flows`() {
        val spend = BalanceInfo("i", "L", BalanceKind.SPEND, "USD", remaining = 1.23)
        assertEquals("-$1.23", formatBalanceAmount(spend, DisplayCurrency.NATIVE, 7.2))
        val quota = BalanceInfo("i", "L", BalanceKind.QUOTA, "FLOW", remaining = 742.8, total = 800.0)
        assertEquals("Flow 742.80/800", formatBalanceAmount(quota, DisplayCurrency.NATIVE, 7.2))
    }

    @Test
    fun `fraction remaining drives the low-balance ladder`() {
        val info = BalanceInfo("i", "L", BalanceKind.BALANCE, "USD", remaining = 1.0, total = 10.0)
        assertEquals(0.1, info.fractionRemaining!!, 1e-9)
        assertNull(BalanceInfo("i", "L", BalanceKind.BALANCE, "USD", remaining = 1.0).fractionRemaining)
    }
}
