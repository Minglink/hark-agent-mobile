package com.openminis.app.goal

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GoalManager — 会话目标设定与长程自主推进管理器
 *
 * 职责：
 * 1. 管理每个会话的专属目标状态 (SessionGoal) 与分步里程碑 (GoalMilestone)；
 * 2. 状态持久化于 `hark-sessions/<sessionId>/goal.json`，冷启动完全保留，零 DB 迁移负担；
 * 3. 驱动 System Prompt 动态注入 [ACTIVE_GOAL_DIRECTIVE]；
 * 4. 联动 EnterPlanModeTool / ExitPlanModeTool 自动解析并同步执行步骤。
 */
object GoalManager {

    private const val TAG = "GoalManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    /** 会话级目标内存缓存与响应式 Flow 映射 */
    private val sessionGoalFlows = ConcurrentHashMap<String, MutableStateFlow<SessionGoal?>>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 获取指定会话的目标响应式 Flow
     */
    fun getGoalFlow(sessionId: String): StateFlow<SessionGoal?> {
        val flow = sessionGoalFlows.getOrPut(sessionId) {
            MutableStateFlow(loadGoalFromDisk(sessionId))
        }
        return flow.asStateFlow()
    }

    /**
     * 同步获取当前目标实体
     */
    fun getCurrentGoal(sessionId: String): SessionGoal? {
        return sessionGoalFlows[sessionId]?.value ?: loadGoalFromDisk(sessionId)
    }

    /**
     * 设定或更新会话主目标
     */
    fun setGoal(
        sessionId: String,
        goalText: String,
        milestones: List<GoalMilestone> = emptyList(),
        status: GoalStatus = GoalStatus.IN_PROGRESS
    ) {
        val existing = getCurrentGoal(sessionId)
        val now = System.currentTimeMillis()
        val newGoal = SessionGoal(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            goalText = goalText.trim(),
            status = status,
            milestones = if (milestones.isNotEmpty()) milestones else existing?.milestones ?: emptyList(),
            currentMilestoneIndex = existing?.currentMilestoneIndex ?: 0,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        updateAndPersist(sessionId, newGoal)
        AppLogger.info(TAG, "[$sessionId] Goal updated: '$goalText' with ${newGoal.milestones.size} milestones")
    }

    /**
     * 从大模型规划输出中自动提取步骤列表并同步到目标
     */
    fun applyProposedSteps(sessionId: String, goalSummary: String, stepsText: String) {
        val milestones = parseStepsToMilestones(stepsText)
        val existing = getCurrentGoal(sessionId)
        val text = if (existing != null && existing.goalText.isNotBlank()) {
            existing.goalText
        } else {
            goalSummary.ifBlank { "执行当前规划任务" }
        }

        setGoal(
            sessionId = sessionId,
            goalText = text,
            milestones = milestones,
            status = GoalStatus.IN_PROGRESS
        )
    }

    /**
     * 更新某个里程碑的状态
     */
    fun updateMilestoneStatus(sessionId: String, milestoneId: String, status: MilestoneStatus) {
        val current = getCurrentGoal(sessionId) ?: return
        val updatedList = current.milestones.map {
            if (it.id == milestoneId) {
                it.copy(
                    status = status,
                    completedAt = if (status == MilestoneStatus.COMPLETED) System.currentTimeMillis() else null
                )
            } else {
                it
            }
        }

        // 自动计算当前索引
        val nextIndex = updatedList.indexOfFirst { it.status != MilestoneStatus.COMPLETED }
            .let { if (it < 0) updatedList.size else it }

        val allCompleted = updatedList.isNotEmpty() && updatedList.all { it.status == MilestoneStatus.COMPLETED }
        val newGoalStatus = if (allCompleted) GoalStatus.COMPLETED else current.status

        val updatedGoal = current.copy(
            milestones = updatedList,
            currentMilestoneIndex = nextIndex,
            status = newGoalStatus,
            updatedAt = System.currentTimeMillis()
        )

        updateAndPersist(sessionId, updatedGoal)
    }

    /**
     * 清理或撤销当前目标
     */
    fun clearGoal(sessionId: String) {
        sessionGoalFlows[sessionId]?.value = null
        scope.launch {
            val file = getGoalFile(sessionId)
            if (file.exists()) file.delete()
        }
        AppLogger.info(TAG, "[$sessionId] Goal cleared")
    }

    /**
     * 生成供 System Prompt 动态注入的目标提示词片段
     */
    fun goalPromptFragment(sessionId: String): String? {
        val goal = getCurrentGoal(sessionId) ?: return null
        if (goal.status == GoalStatus.COMPLETED || goal.status == GoalStatus.ABORTED) return null

        val sb = StringBuilder()
        sb.append("\n\n=== [ACTIVE_GOAL_DIRECTIVE] ===\n")
        sb.append("This session currently has an explicit Autonomous Goal set by the user:\n")
        sb.append("GOAL: \"${goal.goalText}\"\n")
        sb.append("STATUS: ${goal.status.name}\n")

        if (goal.milestones.isNotEmpty()) {
            sb.append("\nMILESTONES & EXECUTION PLAN:\n")
            goal.milestones.forEachIndexed { idx, m ->
                val marker = when (m.status) {
                    MilestoneStatus.COMPLETED -> "[X]"
                    MilestoneStatus.IN_PROGRESS -> "[->]"
                    MilestoneStatus.FAILED -> "[!]"
                    MilestoneStatus.PENDING -> "[ ]"
                }
                sb.append("${idx + 1}. $marker ${m.title}")
                if (m.detail.isNotBlank()) sb.append(" (${m.detail})")
                sb.append("\n")
            }
            goal.currentMilestone?.let { cur ->
                sb.append("\nCURRENT ACTIVE STEP: \"${cur.title}\"\n")
            }
        }

        sb.append("\nEXECUTION DISCIPLINE:\n")
        sb.append("1. Always prioritize advancing towards the goal and current milestone.\n")
        sb.append("2. Inform the user of milestone completions as you make progress.\n")
        sb.append("3. Do NOT get sidetracked by unrelated tasks unless explicitly requested by the user.\n")
        sb.append("=== [END_ACTIVE_GOAL] ===\n")

        return sb.toString()
    }

    /**
     * 将规划文本解析为结构化里程碑列表
     */
    fun parseStepsToMilestones(text: String): List<GoalMilestone> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val result = mutableListOf<GoalMilestone>()

        for (line in lines) {
            val cleaned = line
                .replace(Regex("^[-*•]\\s*"), "")
                .replace(Regex("^\\d+[.)]\\s*"), "")
                .trim()
            if (cleaned.length < 2) continue

            // 检查是否有详细说明冒号分隔
            val parts = cleaned.split("：", ":", limit = 2)
            val title = parts[0].trim()
            val detail = if (parts.size > 1) parts[1].trim() else ""

            result.add(
                GoalMilestone(
                    id = "step_${UUID.randomUUID().toString().take(6)}",
                    title = title,
                    detail = detail,
                    status = MilestoneStatus.PENDING
                )
            )
        }

        return result
    }

    private fun getGoalFile(sessionId: String): File {
        val sessionDir = File(appContext.filesDir, "hark-sessions/$sessionId").also { it.mkdirs() }
        return File(sessionDir, "goal.json")
    }

    private fun updateAndPersist(sessionId: String, goal: SessionGoal) {
        val flow = sessionGoalFlows.getOrPut(sessionId) { MutableStateFlow(null) }
        flow.value = goal

        scope.launch {
            try {
                val file = getGoalFile(sessionId)
                val json = serializeGoal(goal)
                file.writeText(json.toString())
            } catch (e: Exception) {
                AppLogger.warning(TAG, "[$sessionId] Failed to persist goal: ${e.message}")
            }
        }
    }

    private fun loadGoalFromDisk(sessionId: String): SessionGoal? {
        if (!::appContext.isInitialized) return null
        return try {
            val file = getGoalFile(sessionId)
            if (!file.exists()) return null
            val text = file.readText().trim()
            if (text.isEmpty()) return null
            deserializeGoal(JSONObject(text))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[$sessionId] Failed to read goal file: ${e.message}")
            null
        }
    }

    private fun serializeGoal(goal: SessionGoal): JSONObject {
        val json = JSONObject()
        json.put("id", goal.id)
        json.put("sessionId", goal.sessionId)
        json.put("goalText", goal.goalText)
        json.put("status", goal.status.name)
        json.put("currentMilestoneIndex", goal.currentMilestoneIndex)
        json.put("createdAt", goal.createdAt)
        json.put("updatedAt", goal.updatedAt)

        val array = JSONArray()
        for (m in goal.milestones) {
            val mObj = JSONObject()
            mObj.put("id", m.id)
            mObj.put("title", m.title)
            mObj.put("detail", m.detail)
            mObj.put("status", m.status.name)
            mObj.put("isDestructive", m.isDestructive)
            m.completedAt?.let { mObj.put("completedAt", it) }
            array.put(mObj)
        }
        json.put("milestones", array)
        return json
    }

    private fun deserializeGoal(json: JSONObject): SessionGoal {
        val milestones = mutableListOf<GoalMilestone>()
        val array = json.optJSONArray("milestones") ?: JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            milestones.add(
                GoalMilestone(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    detail = obj.optString("detail", ""),
                    status = runCatching { MilestoneStatus.valueOf(obj.getString("status")) }.getOrDefault(MilestoneStatus.PENDING),
                    isDestructive = obj.optBoolean("isDestructive", false),
                    completedAt = if (obj.has("completedAt")) obj.getLong("completedAt") else null
                )
            )
        }

        return SessionGoal(
            id = json.getString("id"),
            sessionId = json.getString("sessionId"),
            goalText = json.getString("goalText"),
            status = runCatching { GoalStatus.valueOf(json.getString("status")) }.getOrDefault(GoalStatus.IN_PROGRESS),
            milestones = milestones,
            currentMilestoneIndex = json.optInt("currentMilestoneIndex", 0),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}
