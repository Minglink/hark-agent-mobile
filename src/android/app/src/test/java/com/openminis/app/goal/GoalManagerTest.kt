package com.openminis.app.goal

import org.junit.Assert.*
import org.junit.Test

class GoalManagerTest {

    @Test
    fun `parseStepsToMilestones parses numbered steps correctly`() {
        val stepsText = """
            1. 调研现有架构代码：阅读 PRootKernel.kt 与 PersistentShell.kt
            2. 编写守护脚本：实现 /usr/local/bin/hark-service
            3. 增加前端监控面板：展示 RAM、存储与服务列表
        """.trimIndent()

        val milestones = GoalManager.parseStepsToMilestones(stepsText)
        assertEquals(3, milestones.size)
        assertEquals("调研现有架构代码", milestones[0].title)
        assertEquals("阅读 PRootKernel.kt 与 PersistentShell.kt", milestones[0].detail)
        assertEquals(MilestoneStatus.PENDING, milestones[0].status)

        assertEquals("编写守护脚本", milestones[1].title)
        assertEquals("实现 /usr/local/bin/hark-service", milestones[1].detail)

        assertEquals("增加前端监控面板", milestones[2].title)
        assertEquals("展示 RAM、存储与服务列表", milestones[2].detail)
    }

    @Test
    fun `parseStepsToMilestones parses bulleted steps`() {
        val stepsText = """
            - 初始化数据库
            * 部署测试服务
            • 执行验证脚本
        """.trimIndent()

        val milestones = GoalManager.parseStepsToMilestones(stepsText)
        assertEquals(3, milestones.size)
        assertEquals("初始化数据库", milestones[0].title)
        assertEquals("部署测试服务", milestones[1].title)
        assertEquals("执行验证脚本", milestones[2].title)
    }

    @Test
    fun `SessionGoal status and milestone count tracking`() {
        val milestones = listOf(
            GoalMilestone(id = "1", title = "Step 1", status = MilestoneStatus.COMPLETED),
            GoalMilestone(id = "2", title = "Step 2", status = MilestoneStatus.IN_PROGRESS),
            GoalMilestone(id = "3", title = "Step 3", status = MilestoneStatus.PENDING)
        )

        val goal = SessionGoal(
            id = "g1",
            sessionId = "s1",
            goalText = "Test Goal",
            status = GoalStatus.IN_PROGRESS,
            milestones = milestones,
            currentMilestoneIndex = 1
        )

        assertEquals(3, goal.totalMilestones)
        assertEquals(1, goal.completedMilestones)
        assertEquals("Step 2", goal.currentMilestone?.title)
    }
}
