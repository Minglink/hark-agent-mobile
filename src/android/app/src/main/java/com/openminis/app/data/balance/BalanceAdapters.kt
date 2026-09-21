package com.openminis.app.data.balance

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [T-balance-chip] Host-routed balance adapters.
 *
 * The app has no per-vendor provider types for DeepSeek/ZenMux/Experiential —
 * users configure them as openAI instances with a custom base URL. Routing is
 * therefore by HOST of the effective base URL, with a generic
 * /dashboard/billing fallback for unbranded relays:
 *
 *   api.deepseek.com        → GET /user/balance                    (native CNY/USD)
 *   openrouter.ai           → GET /api/v1/credits                  (total−usage, USD)
 *   zenmux.ai               → GET /api/v1/management/payg/balance  (management key)
 *                             fallback: /api/v1/management/subscription/detail (flows)
 *   api.experientiallabs.ai → GET /api/v1/credits                  (total−usage, USD;
 *                                                    same key as inference, no org_id)
 *   anything else (openAI)  → GET {origin}/dashboard/billing/subscription [+ /usage]
 *   anthropic/gemini/xAI/…  → no adapter (chip hidden)
 *
 * Parsing is split from fetching (pure `parseX(json, …)` functions) so the
 * unit tests pin every payload shape without network.
 */
object BalanceAdapters {

    /** Outcome of one fetch attempt. */
    sealed class FetchResult {
        data class Ok(val info: BalanceInfo) : FetchResult()
        data class KeyRejected(val message: String) : FetchResult()
        data class Failed(val message: String) : FetchResult()
    }

    /** Which adapter matches this instance, or null (chip hidden). */
    fun routeFor(host: String, providerType: String): String? = when {
        host.contains("api.deepseek.com") -> "deepseek"
        host.contains("openrouter.ai") -> "credits"
        host.contains("zenmux.ai") -> "zenmux"
        host.contains("experientiallabs.ai") -> "credits"
        host.contains("orcarouter.ai") -> "orcarouter"
        providerType == "openAI" || providerType == "openAIResponses" -> "generic"
        else -> null
    }

    /**
     * The management/balance key override for ZenMux-style platforms, or the
     * inference key when no override is configured. [overrideKey] comes from
     * BalanceRepository's per-instance prefs.
     */
    fun effectiveKey(inferenceKey: String, overrideKey: String?): String =
        overrideKey?.trim()?.takeIf { it.isNotEmpty() } ?: inferenceKey

    // ── Fetch ─────────────────────────────────────────────────────────────

    fun fetch(
        route: String,
        baseUrl: String?,
        apiKey: String,
        overrideKey: String?,
        providerLabel: String,
        instanceId: String,
    ): FetchResult {
        val base = baseUrl?.trimEnd('/') ?: return FetchResult.Failed("no base url")
        return try {
            when (route) {
                "deepseek" -> {
                    val body = httpGet("https://api.deepseek.com/user/balance", apiKey)
                        ?: return FetchResult.Failed("empty body")
                    FetchResult.Ok(parseDeepSeek(body, providerLabel, instanceId))
                }
                "credits" -> {
                    // OpenRouter: openrouter.ai/api/v1/credits
                    // Explabs:   api.experientiallabs.ai/api/v1/credits
                    val origin = base.substringBefore("/v1")
                    val body = httpGet("$origin/api/v1/credits", apiKey)
                        ?: return FetchResult.Failed("empty body")
                    FetchResult.Ok(parseCredits(body, providerLabel, instanceId))
                }
                "zenmux" -> {
                    val key = effectiveKey(apiKey, overrideKey)
                    val payg = httpGet("https://zenmux.ai/api/v1/management/payg/balance", key)
                    if (payg != null) {
                        val parsed = parseZenMuxPayg(payg, providerLabel, instanceId)
                        if (parsed != null) return FetchResult.Ok(parsed)
                    }
                    // PAYG absent → try the Builder Plan subscription quota.
                    val sub = httpGet("https://zenmux.ai/api/v1/management/subscription/detail", key)
                    if (sub != null) {
                        val parsed = parseZenMuxSubscription(sub, providerLabel, instanceId)
                        if (parsed != null) return FetchResult.Ok(parsed)
                    }
                    FetchResult.Failed("zenmux: no balance payload")
                }
                "orcarouter" -> {
                    val origin = base.substringBefore("/v1")
                    val balBody = httpGet("$origin/v1/balance", apiKey)
                    val usageBody = httpGet("$origin/v1/dashboard/billing/usage", apiKey)
                        ?: httpGet("$origin/dashboard/billing/usage", apiKey)
                    if (balBody != null) {
                        FetchResult.Ok(parseOrcaRouter(balBody, usageBody, providerLabel, instanceId))
                    } else {
                        val subBody = httpGet("$origin/v1/dashboard/billing/subscription", apiKey)
                            ?: httpGet("$origin/dashboard/billing/subscription", apiKey)
                            ?: return FetchResult.Failed("no balance or subscription endpoint")
                        FetchResult.Ok(parseOrcaRouterFallback(subBody, usageBody, providerLabel, instanceId))
                    }
                }
                "generic" -> fetchGeneric(base, apiKey, providerLabel, instanceId)
                else -> FetchResult.Failed("unknown route")
            }
        } catch (e: KeyRejectedError) {
            FetchResult.KeyRejected(e.message ?: "key rejected")
        } catch (e: Exception) {
            FetchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    class KeyRejectedError(message: String) : Exception(message)

    private fun fetchGeneric(
        base: String,
        apiKey: String,
        label: String,
        instanceId: String,
    ): FetchResult {
        // Relay implementations disagree on whether /dashboard/billing lives
        // under /v1. Try the origin form first (most common), then the /v1 form.
        val origin = base.substringBefore("/v1")
        val subBody = httpGet("$origin/dashboard/billing/subscription", apiKey)
            ?: httpGet("$origin/v1/dashboard/billing/subscription", apiKey)
            ?: return FetchResult.Failed("no billing endpoint")
        val sub = JSONObject(subBody)
        val limit = sub.optDouble("hard_limit_usd", Double.NaN).takeIf { !it.isNaN() }
            ?: return FetchResult.Failed("no hard_limit_usd")
        // Month-to-date usage window, OpenAI original semantics.
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val start = fmt.format(cal.time)
        cal.add(java.util.Calendar.MONTH, 1)
        val end = fmt.format(cal.time)
        val usageBody = httpGet("$origin/dashboard/billing/usage?start_date=$start&end_date=$end", apiKey)
            ?: httpGet("$origin/v1/dashboard/billing/usage?start_date=$start&end_date=$end", apiKey)
        var used = 0.0
        if (usageBody != null) {
            val u = JSONObject(usageBody).optDouble("total_usage", Double.NaN)
            if (!u.isNaN()) {
                // OpenAI's native total_usage is in CENTS; some relays report
                // dollars. Disambiguate against the limit.
                used = if (u / 100.0 <= limit) u / 100.0 else u
            }
        }
        val remaining = limit - used
        return FetchResult.Ok(
            BalanceInfo(
                instanceId = instanceId, providerLabel = label,
                kind = BalanceKind.BALANCE, currency = "USD",
                remaining = remaining, used = used, total = limit,
            )
        )
    }

    /** Minimal GET; throws [KeyRejectedError] on 401/403, null on other failures. */
    private fun httpGet(url: String, apiKey: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                throw KeyRejectedError("HTTP $code${err?.let { ": $it" } ?: ""}")
            }
            if (code !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Parsers (pure, unit-testable) ─────────────────────────────────────

    /**
     * DeepSeek: {"is_available":true,"balance_infos":[{"currency":"CNY",
     * "total_balance":"110.02","granted_balance":"10.00","topped_up_balance":"100.00"}]}
     */
    fun parseDeepSeek(json: String, label: String, instanceId: String): BalanceInfo {
        val root = JSONObject(json)
        val infos = root.optJSONArray("balance_infos")
            ?: throw IllegalStateException("no balance_infos")
        if (infos.length() == 0) throw IllegalStateException("empty balance_infos")
        val first = infos.getJSONObject(0)
        val currency = (first.optString("currency", "USD")).uppercase()
        val total = first.optString("total_balance", "").toDoubleOrNull()
        val granted = first.optString("granted_balance", "").toDoubleOrNull()
        return BalanceInfo(
            instanceId = instanceId, providerLabel = label,
            kind = BalanceKind.BALANCE, currency = currency,
            remaining = total, granted = granted, total = total,
        )
    }

    /**
     * OpenRouter: {"data":{"total_credits":"10.00","total_usage":"1.23"}}
     * Explabs:    same shape ({"total_credits":…,"total_usage":…}) — root or
     * wrapped in "data", numeric or string, both tolerated.
     */
    fun parseCredits(json: String, label: String, instanceId: String): BalanceInfo {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val total = data.optDouble("total_credits", Double.NaN)
        val used = data.optDouble("total_usage", Double.NaN)
        if (total.isNaN() && used.isNaN()) throw IllegalStateException("no credits fields")
        val rem = if (total.isNaN()) null else if (used.isNaN()) total else total - used
        return BalanceInfo(
            instanceId = instanceId, providerLabel = label,
            kind = BalanceKind.BALANCE, currency = "USD",
            remaining = rem?.takeIf { !it.isNaN() },
            used = used.takeIf { !it.isNaN() },
            total = total.takeIf { !it.isNaN() },
        )
    }

    /**
     * ZenMux PAYG: {"success":true,"data":{"currency":"usd","total_credits":482.74,
     * "top_up_credits":35.0,"bonus_credits":447.74}} — total_credits IS the balance.
     */
    fun parseZenMuxPayg(json: String, label: String, instanceId: String): BalanceInfo? {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) return null
        val data = root.optJSONObject("data") ?: return null
        val total = data.optDouble("total_credits", Double.NaN)
        if (total.isNaN()) return null
        val currency = data.optString("currency", "usd").uppercase()
        return BalanceInfo(
            instanceId = instanceId, providerLabel = label,
            kind = BalanceKind.BALANCE, currency = currency,
            remaining = total, total = total,
            granted = data.optDouble("bonus_credits", Double.NaN).takeIf { !it.isNaN() },
        )
    }

    /**
     * ZenMux Builder Plan: quota_5_hour/​quota_7_day flows, not money.
     * {"success":true,"data":{"quota_5_hour":{"max_flows":800,"used_flows":57.2,
     * "remaining_flows":742.8,…},"quota_7_day":{…}}}
     */
    fun parseZenMuxSubscription(json: String, label: String, instanceId: String): BalanceInfo? {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) return null
        val data = root.optJSONObject("data") ?: return null
        val q5 = data.optJSONObject("quota_5_hour") ?: return null
        val max = q5.optDouble("max_flows", Double.NaN)
        val remaining = q5.optDouble("remaining_flows", Double.NaN)
        if (max.isNaN() || remaining.isNaN()) return null
        val q7 = data.optJSONObject("quota_7_day")
        val secondary = q7?.let {
            val m7 = it.optDouble("max_flows", Double.NaN)
            val r7 = it.optDouble("remaining_flows", Double.NaN)
            if (!m7.isNaN() && !r7.isNaN()) "7-day Flow ${trimNumPublic(r7)}/${trimNumPublic(m7)}" else null
        }
        return BalanceInfo(
            instanceId = instanceId, providerLabel = label,
            kind = BalanceKind.QUOTA, currency = "FLOW",
            remaining = remaining, total = max, secondaryLine = secondary,
        )
    }

    /**
     * OrcaRouter Native Balance:
     *   GET /v1/balance -> {"object":"balance","paid_balance":19.929196,"unit":"USD",...}
     *   GET /v1/dashboard/billing/usage -> {"total_usage":7.0804}
     */
    fun parseOrcaRouter(
        balanceJson: String,
        usageJson: String?,
        label: String,
        instanceId: String,
    ): BalanceInfo {
        val root = JSONObject(balanceJson)
        val paidBalance = root.optDouble("paid_balance", Double.NaN).takeIf { !it.isNaN() }
            ?: root.optDouble("balance", Double.NaN).takeIf { !it.isNaN() }
            ?: throw IllegalStateException("no paid_balance in balance payload")

        val currency = root.optString("unit", "USD").uppercase()
        val rawUsed = usageJson?.let {
            val u = JSONObject(it)
            u.optDouble("TotalUsage", Double.NaN).takeIf { !it.isNaN() }
                ?: u.optDouble("total_usage", Double.NaN).takeIf { !it.isNaN() }
        }
        // OpenAIUsageResponse's TotalUsage is in CENTS (100 cents = $1.00 USD).
        // e.g. 7.0804 cents -> $0.070804 USD (approx $0.07).
        val used = rawUsed?.let { it / 100.0 }

        val total = if (used != null && used > 0) paidBalance + used else null

        return BalanceInfo(
            instanceId = instanceId,
            providerLabel = label,
            kind = BalanceKind.BALANCE,
            currency = currency,
            remaining = paidBalance,
            used = used,
            total = total,
        )
    }

    /**
     * OrcaRouter Fallback (subscription endpoint). Note: hard_limit_usd >= 1,000,000 is an
     * uncapped sentinel value (e.g. 100,000,000) and must not be treated as actual balance!
     */
    fun parseOrcaRouterFallback(
        subJson: String,
        usageJson: String?,
        label: String,
        instanceId: String,
    ): BalanceInfo {
        val sub = JSONObject(subJson)
        val rawLimit = sub.optDouble("hard_limit_usd", Double.NaN).takeIf { !it.isNaN() }
            ?: sub.optDouble("hard_limit", Double.NaN).takeIf { !it.isNaN() }
            ?: sub.optDouble("system_hard_limit_usd", Double.NaN).takeIf { !it.isNaN() }
            ?: sub.optDouble("system_hard_limit", Double.NaN).takeIf { !it.isNaN() }

        val rawUsed = usageJson?.let {
            val u = JSONObject(it)
            u.optDouble("TotalUsage", Double.NaN).takeIf { !it.isNaN() }
                ?: u.optDouble("total_usage", Double.NaN).takeIf { !it.isNaN() }
        }
        val used = rawUsed?.let { it / 100.0 }

        // Check if limit is a sentinel infinite value (>= $1,000,000)
        val isSentinel = rawLimit != null && rawLimit >= 1_000_000.0
        val limit = if (isSentinel) null else rawLimit

        val remaining = if (limit != null && used != null) (limit - used).coerceAtLeast(0.0) else null
        val kind = if (remaining != null) BalanceKind.BALANCE else BalanceKind.SPEND

        return BalanceInfo(
            instanceId = instanceId,
            providerLabel = label,
            kind = kind,
            currency = "USD",
            remaining = remaining ?: used,
            used = used,
            total = limit,
        )
    }

    private fun trimNumPublic(v: Double): String {
        val r = (v * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format("%.2f", r)
    }
}
