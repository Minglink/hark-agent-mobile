package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ui-craft Strict Card Specifications (Rule 4 & Rule 265):
 * - Card corners: 12dp (dense rows), 16dp (standard cards), 20dp (artwork/sheets)
 * - Border: 0.75dp hairline in outlineVariant
 * - Content padding: 16dp gutter
 */
val HarkCardCorner = 16.dp
val HarkDenseCardCorner = 12.dp

@Composable
fun HarkCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(HarkCardCorner),
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    borderWidth: Dp = 0.75.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .padding(contentPadding)
    ) {
        Column {
            content()
        }
    }
}
