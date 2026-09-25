package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// iOS reference:
//   systemBackground        -> background
//   secondarySystemBackground -> secondaryBg
//   tertiarySystemFill      -> userBubble
//   tertiarySystemGroupedBackground -> toolBg
//   label                   -> primaryText
//   secondaryLabel          -> secondaryText
//   tertiaryLabel           -> tertiaryText
//   quaternaryLabel         -> sendButtonDisabled
//   separator               -> border
//   systemGray6             -> inlineCodeBg / toolCapsuleBg
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color(0xFFF8FAFC),
    secondaryBg = Color.White,
    inputBg = Color.White,
    inputIconBg = Color(0xFFF1F5F9),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0xFFE2E8F0),
    primaryText = Color(0xFF0F172A),
    secondaryText = Color(0xFF475569),
    tertiaryText = Color(0xFF64748B),
    disabledText = Color(0xFF94A3B8),
    userBubble = Color(0xFFEBF2FF),
    toolBg = Color(0xFFF1F5F9),
    toolBorder = Color(0xFFE2E8F0),
    toolCapsuleBg = Color(0xFFF1F5F9),
    separator = Color(0xFFE2E8F0),
    sendButton = Color(0xFF2563EB),
    sendButtonDisabled = Color(0xFFCBD5E1),
    codeBlockBg = Color(0xFF0F172A),
    codeBlockText = Color(0xFF10B981),
    inlineCodeBg = Color(0xFFF1F5F9),
    inlineCodeText = Color(0xFFD97706),
    link = Color(0xFF2563EB),
    blockquoteBar = Color(0xFF3B82F6),
    thinking = Color(0xFF2563EB),
    warningBg = Color(0xFFFEF3C7),
    warningText = Color(0xFF92400E),
    tableBorder = Color(0xFFE2E8F0),
    inputShadow = Color(0x0A000000),
    toastBg = Color(0x1A2563EB),
    thumbnailBorder = Color(0xFFE2E8F0),
    sheetHeaderBg = Color.White,
    sheetHeaderBorder = Color(0xFFE2E8F0),
    fabAccent = Color(0xFF2563EB),
)

// Hark 3.5 Glacial Midnight Palette
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF0A0E17),        // Glacial Deep Night
    secondaryBg = Color(0xFF111827),       // Slate-900 Card Surface
    inputBg = Color(0xFF1E293B),           // Slate-800 Elevated Surface
    inputIconBg = Color(0xFF243247),
    inputIconBorder = Color(0x2638BDF8),   // Glacial Ice Blue border
    inputBorder = Color(0x2638BDF8),
    primaryText = Color(0xFFF8FAFC),       // Crisp Ice White text
    secondaryText = Color(0xFFCBD5E1),     // Luminous Silver secondary
    tertiaryText = Color(0xFF94A3B8),
    disabledText = Color(0xFF475569),
    userBubble = Color(0xFF1E2D4A),        // Translucent Midnight Cobalt
    toolBg = Color(0xFF111827),
    toolBorder = Color(0x2638BDF8),
    toolCapsuleBg = Color(0xFF1E293B),
    separator = Color(0x2638BDF8),
    sendButton = Color(0xFF38BDF8),
    sendButtonDisabled = Color(0xFF334155),
    codeBlockBg = Color(0xFF070B12),
    codeBlockText = Color(0xFF10B981),
    inlineCodeBg = Color(0xFF1E293B),
    inlineCodeText = Color(0xFFF59E0B),
    link = Color(0xFF38BDF8),
    blockquoteBar = Color(0xFF38BDF8),
    thinking = Color(0xFF38BDF8),
    warningBg = Color(0x1EF59E0B),
    warningText = Color(0xFFFDE68A),
    tableBorder = Color(0x2638BDF8),
    inputShadow = Color(0x40000000),
    toastBg = Color(0x2E38BDF8),
    thumbnailBorder = Color(0x2638BDF8),
    sheetHeaderBg = Color(0xFF111827),
    sheetHeaderBorder = Color(0x2638BDF8),
    fabAccent = Color(0xFF38BDF8),         // Ice Blue Signal
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
