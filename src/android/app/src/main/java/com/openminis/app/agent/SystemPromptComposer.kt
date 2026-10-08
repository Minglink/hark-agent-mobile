package com.openminis.app.agent

/** Keep the stable instruction prefix independent of skills, memory, goals and runtime metadata. */
internal object SystemPromptComposer {
    fun composeBase(
        builtIn: String,
        custom: SystemPromptFile?,
        project: ProjectPromptInfo?,
        projectName: String,
    ): String {
        val customBlock = custom?.let(SystemPromptStore::injectionBlock)
        val withCustom = when {
            customBlock == null -> builtIn
            custom?.mode == SystemPromptMode.PREPEND -> "$customBlock\n\n$builtIn"
            else -> "$builtIn\n\n$customBlock"
        }
        if (project == null) return withCustom
        val prefix = if (project.mode == ProjectPromptMode.OVERRIDE) builtIn else withCustom
        return prefix + "\n\n" + ProjectPromptManager.renderPromptBlock(project, projectName)
    }
}
