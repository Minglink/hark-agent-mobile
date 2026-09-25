package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * Standardized half-screen modal sheet used by every popup launched from the
 * chat input "⋯" menu. Mirrors [CompactSummarySheet]: 90% screen height by
 * default, the same header row (optional leading action / centered title /
 * close button), 0.5dp separator, and a body slot that fills the rest. The
 * body stays independent — each call site supplies its own [content].
 *
 * Uses a compact custom drag handle: the Material3 default reserves ~22dp of
 * padding above and below the indicator, which produced too much whitespace
 * between the indicator and the title — this version tightens it to 6dp / 4dp.
 *
 * [heightFraction] lets a caller request a smaller detent — for example
 * [TokenUsageSheet] passes 0.5f to match iOS's `.medium` detent
 * (AIChatView.swift:508). The fraction is clamped to (0, 1] so callers can't
 * accidentally collapse the sheet to nothing.
 */
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.openminis.app.ui.components.pressScaleEffect
import com.openminis.app.ui.theme.UiCraftTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardChatSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    heightFraction: Float = 0.9f,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * heightFraction.coerceIn(0.1f, 1f)).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.background,
        shape = RoundedCornerShape(
            topStart = UiCraftTokens.SheetCornerRadius,
            topEnd = UiCraftTokens.SheetCornerRadius,
        ),
        dragHandle = { CompactDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            StandardChatSheetHeader(
                title = title,
                onDismiss = onDismiss,
                leadingAction = leadingAction,
            )
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * Slim replacement for [androidx.compose.material3.BottomSheetDefaults.DragHandle].
 * ui-craft standard 36×4 indicator pill.
 */
@Composable
private fun CompactDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(UiCraftTokens.GrabberWidth)
                .height(UiCraftTokens.GrabberHeight)
                .background(
                    color = ChatColors.secondaryText.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

/**
 * Shared header row used by all chat sheets — 28dp close disc on the right,
 * centered 17sp bold title, and an optional leading slot.
 */
@Composable
fun StandardChatSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingAction != null) {
            leadingAction()
        } else {
            Spacer(modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = ChatColors.primaryText,
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(ChatColors.secondaryBg, CircleShape)
                .clip(CircleShape)
                .pressScaleEffect(targetScale = 0.92f)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.standard_sheet_close),
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
