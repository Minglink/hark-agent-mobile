package com.openminis.app.data.balance

/**
 * [T-balance-chip] Balance data model for the chat top-bar chip.
 *
 * Three semantic kinds — a provider's balance API tells you one of:
 *  - BALANCE: prepaid credits remaining (DeepSeek, OpenRouter, ZenMux, Explabs)
 *  - SPEND:   usage only, no cap known (Explabs BYOK-heavy accounts)
 *  - QUOTA:   subscription flow quota, not money (ZenMux Builder Plan)
 */
enum class BalanceKind { BALANCE, SPEND, QUOTA }

data class BalanceInfo(
    val instanceId: String,
    val providerLabel: String,
    val kind: BalanceKind,
    /** ISO-ish currency code for BALANCE/SPEND ("USD"/"CNY"); "FLOW" for QUOTA. */
    val currency: String,
    /**
     * BALANCE → credits remaining. SPEND → amount used (positive number,
     * rendered with a leading '-'). QUOTA → remaining flows.
     */
    val remaining: Double?,
    val used: Double? = null,
    val granted: Double? = null,
    val total: Double? = null,
    /** Second quota window line (ZenMux 7-day flows), already formatted. */
    val secondaryLine: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    /** True when the endpoint rejected the key — chip hides, sheet explains. */
    val keyRejected: Boolean = false,
) {
    /** Fraction remaining (0..1), or null when the cap is unknown. */
    val fractionRemaining: Double?
        get() {
            val total = total ?: return null
            val rem = remaining ?: return null
            if (total <= 0.0) return null
            return (rem / total).coerceIn(0.0, 1.0)
        }
}

/** Display-currency preference shared by the chip and the detail sheet. */
enum class DisplayCurrency { NATIVE, USD, CNY }

object BalancePrefs {
    const val PREFS_NAME = "balance_prefs"
    const val KEY_DISPLAY = "display_currency"
    const val KEY_FX = "usd_cny_rate"
    const val KEY_OVERRIDE_PREFIX = "balance_key_"
    const val DEFAULT_FX = 7.2
}

/** Format a BalanceInfo as the chip/sheet amount string, honoring the preference. */
fun formatBalanceAmount(
    info: BalanceInfo,
    display: DisplayCurrency,
    usdCnyRate: Double,
): String {
    if (info.kind == BalanceKind.QUOTA) {
        val rem = info.remaining ?: return ""
        val max = info.total
        val remStr = trimNum(rem)
        return if (max != null && max > 0) "Flow $remStr/${trimNum(max)}" else "Flow $remStr"
    }
    val sign = if (info.kind == BalanceKind.SPEND) "-" else ""
    val value = info.remaining ?: return ""
    return when (info.currency) {
        "CNY" -> when (display) {
            DisplayCurrency.USD -> "$sign~$${trimNum(value / usdCnyRate)}"
            else -> "$sign¥${trimNum(value)}" // NATIVE and CNY both show CNY as-is
        }
        else -> when (display) { // USD and anything unspecified
            DisplayCurrency.CNY -> "$sign~¥${trimNum(value * usdCnyRate)}"
            else -> "$sign$${trimNum(value)}" // NATIVE and USD both show USD as-is
        }
    }
}

private fun trimNum(v: Double): String {
    val rounded = (v * 100).let { (it.coerceAtLeast(0.0)) } / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format("%.2f", rounded)
    }
}
