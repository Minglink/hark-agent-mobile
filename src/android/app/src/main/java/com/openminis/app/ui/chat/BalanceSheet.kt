package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.balance.BalanceInfo
import com.openminis.app.data.balance.BalanceKind
import com.openminis.app.data.balance.DisplayCurrency
import com.openminis.app.data.balance.formatBalanceAmount
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * [T-balance-chip] Balance surfaces for the chat top bar:
 *  - [BalanceChip] — the compact amount in the TopAppBar actions slot.
 *  - [BalanceSheetModal] — the detail sheet (current channel, other channels,
 *    currency preference + manual FX rate, per-instance balance key override).
 *
 * Silent-degradation contract: a null/hidden chip means "no data", never an
 * error box in the chat.
 */

/** Low-balance tint ladder, matching the settings pages' accent palette. */
@Composable
fun balanceAccent(info: BalanceInfo): Color = when {
    info.keyRejected -> MaterialTheme.colorScheme.error
    info.kind == BalanceKind.QUOTA -> {
        val frac = info.fractionRemaining
        when {
            frac != null && frac < 0.10 -> Color(0xFFFF3B30)
            frac != null && frac < 0.20 -> Color(0xFFFF9500)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    else -> {
        val frac = info.fractionRemaining
        when {
            frac != null && frac < 0.10 -> Color(0xFFFF3B30)
            frac != null && frac < 0.20 -> Color(0xFFFF9500)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

@Composable
fun BalanceChip(
    info: BalanceInfo?,
    display: DisplayCurrency,
    fxRate: Double,
    onClick: () -> Unit,
) {
    if (info == null || info.keyRejected || info.remaining == null) return
    val text = formatBalanceAmount(info, display, fxRate)
    if (text.isEmpty()) return
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        color = balanceAccent(info),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceSheetModal(
    current: BalanceInfo?,
    others: List<BalanceInfo>,
    display: DisplayCurrency,
    fxRate: Double,
    balanceKeyOverride: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDisplayChange: (DisplayCurrency) -> Unit,
    onFxChange: (Double) -> Unit,
    onOverrideKeyChange: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.balance_sheet_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                MinisTextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.balance_refresh))
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Current channel ──
            SectionLabel(stringResource(R.string.balance_current_channel))
            when {
                current == null -> Text(
                    text = stringResource(R.string.balance_unavailable),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                current.keyRejected -> Text(
                    text = stringResource(R.string.balance_invalid_key),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> ChannelCard(current, display, fxRate)
            }

            Spacer(Modifier.height(20.dp))

            // ── Other channels ──
            if (others.isNotEmpty()) {
                SectionLabel(stringResource(R.string.balance_other_channels))
                others.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = info.providerLabel,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                info.keyRejected -> stringResource(R.string.balance_invalid_key_short)
                                info.remaining == null -> "…"
                                else -> formatBalanceAmount(info, display, fxRate)
                            },
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = balanceAccent(info),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Display currency ──
            SectionLabel(stringResource(R.string.balance_display_currency))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    DisplayCurrency.NATIVE to stringResource(R.string.balance_cur_native),
                    DisplayCurrency.USD to stringResource(R.string.balance_cur_usd),
                    DisplayCurrency.CNY to stringResource(R.string.balance_cur_cny),
                )
                options.forEach { (key, labelRes) ->
                    if (key == display) {
                        MinisButton(onClick = { onDisplayChange(key) }, modifier = Modifier.weight(1f)) {
                            Text(labelRes)
                        }
                    } else {
                        MinisOutlinedButton(onClick = { onDisplayChange(key) }, modifier = Modifier.weight(1f)) {
                            Text(labelRes)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Manual FX rate ──
            var fxText by remember(fxRate) { mutableStateOf(if (fxRate == fxRate.toLong().toDouble()) fxRate.toLong().toString() else fxRate.toString()) }
            OutlinedTextField(
                value = fxText,
                onValueChange = { v ->
                    fxText = v
                    v.toDoubleOrNull()?.let { onFxChange(it) }
                },
                label = { Text(stringResource(R.string.balance_fx_rate)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            // ── Balance key override (ZenMux management key, etc.) ──
            if (current != null) {
                var keyText by remember(current.instanceId, balanceKeyOverride) {
                    mutableStateOf(balanceKeyOverride)
                }
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.balance_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                MinisTextButton(onClick = { onOverrideKeyChange(keyText.ifBlank { null }) }) {
                    Text(stringResource(R.string.balance_key_save))
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(info: BalanceInfo, display: DisplayCurrency, fxRate: Double) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = info.providerLabel,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatBalanceAmount(info, display, fxRate),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = balanceAccent(info),
                )
            }
            info.secondaryLine?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val detail = buildList {
                info.total?.let { add(stringResource(R.string.balance_total, formatMoney(it, info, display, fxRate))) }
                info.used?.let { add(stringResource(R.string.balance_used, formatMoney(it, info, display, fxRate))) }
                info.granted?.let { add(stringResource(R.string.balance_granted, formatMoney(it, info, display, fxRate))) }
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail.joinToString("   ·   "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatMoney(v: Double, info: BalanceInfo, display: DisplayCurrency, fx: Double): String {
    // Detail rows always show the NATIVE currency to stay unambiguous.
    return when (info.currency) {
        "CNY" -> "¥${v}"
        "FLOW" -> v.toString()
        else -> "$${v}"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
