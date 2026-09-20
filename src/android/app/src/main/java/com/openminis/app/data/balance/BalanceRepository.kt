package com.openminis.app.data.balance

import android.content.Context
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-balance-chip] Cache + throttle + routing in front of [BalanceAdapters].
 *
 *  - 5-minute TTL cache per instance (chip re-entry is free).
 *  - Negative cache: a key-rejected instance is not retried for 24 h (never
 *    hammer an endpoint that 401s), but a successful manual refresh clears it.
 *  - Display preference (native/USD/CNY + manual FX rate) lives here too —
 *    one prefs file for the whole feature.
 */
class BalanceRepository(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) {

    private data class Entry(val at: Long, val info: BalanceInfo?, val keyRejected: Boolean)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val prefs get() = context.getSharedPreferences(BalancePrefs.PREFS_NAME, Context.MODE_PRIVATE)

    // ── Display preference ────────────────────────────────────────────────

    fun displayCurrency(): DisplayCurrency = runCatching {
        DisplayCurrency.valueOf(prefs.getString(BalancePrefs.KEY_DISPLAY, "NATIVE") ?: "NATIVE")
    }.getOrDefault(DisplayCurrency.NATIVE)

    fun setDisplayCurrency(v: DisplayCurrency) {
        prefs.edit().putString(BalancePrefs.KEY_DISPLAY, v.name).apply()
    }

    fun usdCnyRate(): Double =
        prefs.getFloat(BalancePrefs.KEY_FX, BalancePrefs.DEFAULT_FX.toFloat()).toDouble()
            .takeIf { it > 0.0 } ?: BalancePrefs.DEFAULT_FX

    fun setUsdCnyRate(v: Double) {
        if (v > 0.0) prefs.edit().putFloat(BalancePrefs.KEY_FX, v.toFloat()).apply()
    }

    // ── Per-instance balance key override (ZenMux management key, etc.) ───

    fun balanceKeyOverride(instanceId: String): String? =
        prefs.getString(BalancePrefs.KEY_OVERRIDE_PREFIX + instanceId, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setBalanceKeyOverride(instanceId: String, key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        if (k == null) prefs.edit().remove(BalancePrefs.KEY_OVERRIDE_PREFIX + instanceId).apply()
        else prefs.edit().putString(BalancePrefs.KEY_OVERRIDE_PREFIX + instanceId, k).apply()
        invalidate(instanceId)
    }

    // ── Fetch ─────────────────────────────────────────────────────────────

    /** Cached fetch for the chip. Null = hide the chip (no adapter / failing). */
    suspend fun current(instance: ProviderInstance): BalanceInfo? {
        val e = cache[instance.id]
        val now = System.currentTimeMillis()
        if (e != null) {
            val ttl = if (e.keyRejected) KEY_REJECT_TTL_MS else TTL_MS
            if (now - e.at < ttl) return e.info
        }
        return fetchInternal(instance)
    }

    /** Force refresh (sheet button). Clears any negative cache first. */
    suspend fun forceRefresh(instance: ProviderInstance): BalanceInfo? {
        cache.remove(instance.id)
        return fetchInternal(instance)
    }

    fun invalidate(instanceId: String) {
        cache.remove(instanceId)
    }

    fun cached(instanceId: String): BalanceInfo? = cache[instanceId]?.info

    private suspend fun fetchInternal(instance: ProviderInstance): BalanceInfo? =
        withContext(Dispatchers.IO) {
            val host = runCatching {
                java.net.URI(instance.effectiveBaseURL ?: "").host ?: ""
            }.getOrDefault("")
            val route = BalanceAdapters.routeFor(host, instance.providerType.name)
                ?: return@withContext null
            val apiKey = providerRepository.loadApiKey(instance.id)
                ?: return@withContext null
            val result = BalanceAdapters.fetch(
                route = route,
                baseUrl = instance.effectiveBaseURL,
                apiKey = apiKey,
                overrideKey = balanceKeyOverride(instance.id),
                providerLabel = instance.label,
                instanceId = instance.id,
            )
            when (result) {
                is BalanceAdapters.FetchResult.Ok -> {
                    cache[instance.id] = Entry(System.currentTimeMillis(), result.info, false)
                    result.info
                }
                is BalanceAdapters.FetchResult.KeyRejected -> {
                    cache[instance.id] = Entry(
                        System.currentTimeMillis(),
                        BalanceInfo(
                            instanceId = instance.id, providerLabel = instance.label,
                            kind = BalanceKind.BALANCE, currency = "USD",
                            remaining = null, keyRejected = true,
                        ),
                        true,
                    )
                    null
                }
                is BalanceAdapters.FetchResult.Failed -> {
                    // Transient failure: short negative cache so a broken
                    // endpoint doesn't get retried on every chat navigation.
                    cache[instance.id] = Entry(System.currentTimeMillis(), null, false)
                    null
                }
            }
        }

    companion object {
        private const val TTL_MS = 5 * 60_000L
        private const val KEY_REJECT_TTL_MS = 24 * 60 * 60_000L
    }
}
