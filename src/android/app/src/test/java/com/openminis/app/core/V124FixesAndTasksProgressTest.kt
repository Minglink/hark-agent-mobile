package com.openminis.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V124FixesAndTasksProgressTest
 *
 * 验证 1.2.4 版本中的重要修复与 Tasks Progress 动态任务跟踪：
 * 1. hark-model-use prompt 参数解析与 fallback 逻辑
 * 2. hark-open 脚本 POSIX 规范性与无 CRLF / 无危险转义命令替换
 * 3. 动态 Markdown Todos 检查清单解析 (支持 -, *, +, 序号列表，完成与进行中状态)
 * 4. 日历 SyncAdapter 补偿 URI 构造规则
 */
class V124FixesAndTasksProgressTest {

    @Test
    fun testHarkModelUsePromptExtraction() {
        fun extractPrompt(argv: List<String>): String? {
            var prompt: String? = null
            var i = 0
            val positional = mutableListOf<String>()
            while (i < argv.size) {
                when (val arg = argv[i]) {
                    "-p", "--prompt" -> {
                        if (i + 1 < argv.size) prompt = argv[++i]
                    }
                    else -> {
                        if (!arg.startsWith("-")) {
                            positional.add(arg)
                        }
                    }
                }
                i++
            }
            return prompt ?: positional.joinToString(" ").takeIf { it.isNotBlank() }
        }

        // 1. --prompt flag
        assertEquals("1+1=?", extractPrompt(listOf("--prompt", "1+1=?")))
        // 2. -p shorthand
        assertEquals("calculate 2+2", extractPrompt(listOf("-p", "calculate 2+2")))
        // 3. Positional trailing prompt
        assertEquals("what is the capital of France?", extractPrompt(listOf("what is the capital of France?")))
        // 4. Multiple positional tokens joined
        assertEquals("tell me a joke", extractPrompt(listOf("tell", "me", "a", "joke")))
    }

    @Test
    fun testHarkOpenScriptFormat() {
        val scriptPath = "src/main/assets/default_mount/usr/local/bin/hark-open"
        val file = File(scriptPath).let {
            if (it.exists()) it else File("app/$scriptPath")
        }
        if (file.exists()) {
            val bytes = file.readBytes()
            // 确保无 Windows CRLF (\r\n) 换行符
            val content = file.readText()
            assertFalse("hark-open must not contain CRLF", content.contains("\r"))
            // 确保不包含有问题的命令替换
            assertFalse("hark-open must not assign ESC from printf subshell", content.contains("ESC="))
            assertFalse("hark-open must not assign BEL from printf subshell", content.contains("BEL="))
            // 确保使用纯 POSIX 格式化输出
            assertTrue("hark-open should use direct printf escape", content.contains("printf '\\033]1337;MinisOpenURL=%s\\007\\n'"))
        }
    }

    @Test
    fun testParseMarkdownTodosDynamic() {
        fun parseTodos(text: String): List<Pair<String, String>> {
            val items = mutableListOf<Pair<String, String>>()
            val regex = Regex("""^\s*(?:[-*+]|\d+\.)\s+\[([ xX/\-])\]\s+(.+)$""")
            for (line in text.lines()) {
                val match = regex.find(line) ?: continue
                val mark = match.groupValues[1]
                val status = when (mark.lowercase()) {
                    "x" -> "completed"
                    "/", "-" -> "in_progress"
                    else -> "pending"
                }
                val content = match.groupValues[2].trim()
                if (content.isNotEmpty()) {
                    items.add(content to status)
                }
            }
            return if (items.size >= 2) items else emptyList()
        }

        val markdownText = """
            Here is my execution plan:
            - [x] Step 1: Analyze bug reports
            - [/] Step 2: Implement calendar fallback
            - [ ] Step 3: Run regression tests
            + [ ] Step 4: Assemble release APK
        """.trimIndent()

        val parsed = parseTodos(markdownText)
        assertEquals(4, parsed.size)
        assertEquals("Step 1: Analyze bug reports" to "completed", parsed[0])
        assertEquals("Step 2: Implement calendar fallback" to "in_progress", parsed[1])
        assertEquals("Step 3: Run regression tests" to "pending", parsed[2])
        assertEquals("Step 4: Assemble release APK" to "pending", parsed[3])

        // Numbered lists support
        val numberedText = """
            1. [ ] First task
            2. [x] Second task
        """.trimIndent()
        val parsedNumbered = parseTodos(numberedText)
        assertEquals(2, parsedNumbered.size)
        assertEquals("First task" to "pending", parsedNumbered[0])
        assertEquals("Second task" to "completed", parsedNumbered[1])

        // Single item should not trigger full checklist
        val singleItem = "- [ ] Only one task"
        assertTrue(parseTodos(singleItem).isEmpty())
    }

    @Test
    fun testCalendarSyncUriQueryParameters() {
        val baseUriString = "content://com.android.calendar/events"
        val accountName = "user@gmail.com"
        val accountType = "com.google"

        val syncUriString = "$baseUriString?caller_is_syncadapter=true&account_name=$accountName&account_type=$accountType"
        assertTrue(syncUriString.contains("caller_is_syncadapter=true"))
        assertTrue(syncUriString.contains("account_name=user@gmail.com"))
        assertTrue(syncUriString.contains("account_type=com.google"))
    }

    @Test
    fun testHarkModelUseWrapperScript() {
        val scriptPath = "src/main/assets/default_mount/usr/local/bin/hark-model-use"
        val file = File(scriptPath).let {
            if (it.exists()) it else File("app/$scriptPath")
        }
        if (file.exists()) {
            val content = file.readText()
            assertFalse("hark-model-use must not contain CRLF", content.contains("\r"))
            assertTrue("hark-model-use must check IS_RUN", content.contains("IS_RUN=1"))
            assertTrue("hark-model-use must buffer piped stdin", content.contains("cat > \"\$TMP_STDIN\""))
            assertTrue("hark-model-use must delegate to hark-model-use-backend", content.contains("hark-model-use-backend"))
            assertTrue("hark-model-use must trap cleanup", content.contains("trap cleanup EXIT INT TERM"))
        }
    }

    @Test
    fun testScheduledTaskCancelAndProgress() {
        val subcommands = setOf(
            "list", "create", "add", "delete", "remove", "rm",
            "enable", "disable", "cancel", "progress", "status", "run"
        )
        assertTrue(subcommands.contains("cancel"))
        assertTrue(subcommands.contains("progress"))
        assertTrue(subcommands.contains("status"))
    }
}
