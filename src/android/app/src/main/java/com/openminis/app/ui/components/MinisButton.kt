package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue

// ui-craft Rule 13: Buttons are compact (main 44, dark 40, others 36, chips 30)
// The main action is 44 pt with minimum 44-pt touch area via hitSlop/touch bounds.
val MinisButtonHeight = 44.dp
val MinisSecondaryButtonHeight = 40.dp
val MinisCompactButtonHeight = 36.dp
val MinisSmallButtonHeight = 32.dp
val MinisChipHeight = 30.dp

private val SmallButtonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
private val CompactButtonContentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/**
 * ui-craft physical press scale effect:
 * Elements ease to ~96% on press and spring back, giving a tactile mechanical feel.
 */
@Composable
fun Modifier.pressScaleEffect(
    targetScale: Float = 0.96f,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) targetScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}

@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}

// defaultMinSize is also pinned at MinisSmallButtonHeight so Material3's
// internal 40dp floor (ButtonDefaults.MinHeight) doesn't override the
// heightIn modifier and keep the button at 40dp.
@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}

@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}

/**
 * ui-craft compact button (36pt) for browsing actions and in-card buttons.
 */
@Composable
fun MinisCompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CompactButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val actualInteraction = interactionSource ?: remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .pressScaleEffect(enabled = enabled, interactionSource = actualInteraction)
            .heightIn(min = MinisCompactButtonHeight)
            .defaultMinSize(minHeight = MinisCompactButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = actualInteraction,
        content = content,
    )
}
