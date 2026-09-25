package com.openminis.app.tools.plan

import kotlinx.serialization.Serializable

/**
 * 规划模式状态实体
 * 借鉴 Open-ClaudeCode (src/tools/EnterPlanModeTool)
 */
@Serializable
data class PlanPhaseState(
    val isPlanModeActive: Boolean = false,
    val planGoal: String? = null,
    val proposedSteps: List<PlanStep> = emptyList(),
    val affectedFiles: List<String> = emptyList(),
    val isApproved: Boolean = false
)

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val isDestructive: Boolean = false
)

/**
 * 结构化向用户提问参数
 * 借鉴 Open-ClaudeCode (src/tools/AskUserQuestionTool)
 */
@Serializable
data class AskUserQuestionParams(
    val question: String,
    val options: List<QuestionOption>,
    val isMultiSelect: Boolean = false
)

@Serializable
data class QuestionOption(
    val id: String,
    val label: String,
    val description: String? = null,
    val isRecommended: Boolean = false
)
