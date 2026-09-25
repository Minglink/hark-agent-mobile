package com.openminis.app.core

import com.openminis.app.automation.DeviceActionDispatcher
import com.openminis.app.automation.PhoneAgentTool
import com.openminis.app.plugins.harkpkg.HarkPkgManager
import com.openminis.app.plugins.hooks.AgentHook
import com.openminis.app.plugins.hooks.AgentHookPipeline
import com.openminis.app.plugins.hooks.HookContext
import com.openminis.app.plugins.hooks.ToolInterceptDecision
import com.openminis.app.rag.chunking.DocumentChunker
import com.openminis.app.rag.retrieval.HybridRetrievalEngine
import com.openminis.app.rag.tools.RagTools
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 业务与系统级端到端测试：验证 1.2.5 版本三大核心支柱能力（设备控制、插件生态、长程记忆 RAG）
 * 的全流程协作、边界防护、内存保护与稳定性。
 */
class V125ThreePillarsBusinessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testContext = HookContext(sessionId = "v125-test-session")

    @Before
    fun setUp() {
        AgentHookPipeline.clearHooks()
        RagTools.clearSession("v125-test-session")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 支柱一业务测试：设备控制与 PhoneAgent 防护
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun testPillar1_AgentToolsContainsPhoneScreenAction() {
        val tools = AgentTools.makeAgentTools()
        val phoneTool = tools.find { it.name == PhoneAgentTool.NAME }
        assertNotNull("phone_screen_action must be registered in AgentTools", phoneTool)
        assertTrue(phoneTool!!.parameters.containsKey("action"))
        assertTrue(phoneTool.parameters.containsKey("silent_background"))
    }

    @Test
    fun testPillar1_CoordinateValidationAndNegativeRejection() = runBlocking {
        // Negative tap coordinates must be rejected
        val tapRes = DeviceActionDispatcher.tap(-5, 100)
        assertFalse(tapRes.success)
        assertTrue(tapRes.message.contains("Invalid coordinates"))

        // Negative swipe coordinates must be rejected
        val swipeRes = DeviceActionDispatcher.swipe(100, -20, 200, 300)
        assertFalse(swipeRes.success)
        assertTrue(swipeRes.message.contains("Invalid coordinates"))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 支柱二业务测试：.harkpkg 扩展生态与 Hooks 拦截总线
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun testPillar2_FullHookPipelineAndSecurityGuard() {
        // 1. Verify dangerous shell commands are blocked by DefaultSecurityGuardHook
        val blockDecision = AgentHookPipeline.processBeforeToolExecute(
            "shell_execute",
            """{"command": "rm -rf / --no-preserve-root"}""",
            testContext,
        )
        assertTrue(blockDecision is ToolInterceptDecision.Block)
        assertTrue((blockDecision as ToolInterceptDecision.Block).reason.contains("Security Violation"))

        // 2. Register custom business hook
        val auditHook = object : AgentHook {
            override val id: String = "test.audit_hook"
            override val priority: Int = 300

            override fun onPromptInput(input: String, context: HookContext): String {
                return input.trim() + " #verified"
            }

            override fun onSystemPromptCompose(basePrompt: String, context: HookContext): String {
                return "$basePrompt\n[AUDIT_ACTIVE]"
            }
        }
        AgentHookPipeline.registerHook(auditHook)

        // Verify prompt input pipeline
        val processedInput = AgentHookPipeline.processPromptInput("Deploy app", testContext)
        assertEquals("Deploy app #verified", processedInput)

        // Verify system prompt composition pipeline
        val composedPrompt = AgentHookPipeline.processSystemPromptCompose("Base system prompt", testContext)
        assertTrue(composedPrompt.contains("[AUDIT_ACTIVE]"))

        // Unregister and ensure clean removal
        AgentHookPipeline.unregisterHook("test.audit_hook")
        val cleanPrompt = AgentHookPipeline.processSystemPromptCompose("Base system prompt", testContext)
        assertFalse(cleanPrompt.contains("[AUDIT_ACTIVE]"))
    }

    @Test
    fun testPillar2_HarkPkgDynamicToolsExposedToAgent() {
        val targetDir = tempFolder.newFolder("pkg_test")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"id": "plugin.dynamic", "name": "Dynamic Plugin", "version": "1.0.0"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tools/dynamic_tools.json"))
            zos.write("""
                {
                    "tools": [
                        {
                            "name": "plugin_special_action",
                            "description": "Performs specialized plugin operation",
                            "parameters": {"properties": {"arg1": {"type": "string"}}}
                        }
                    ]
                }
            """.trimIndent().toByteArray())
            zos.closeEntry()
        }

        // Install package
        HarkPkgManager.installPackage(ByteArrayInputStream(bos.toByteArray()), targetDir)

        // Verify dynamic tool is visible in AgentTools
        val tools = AgentTools.makeAgentTools()
        val dynamicTool = tools.find { it.name == "plugin_special_action" }
        assertNotNull("Dynamic tool from .harkpkg must be exposed in AgentTools", dynamicTool)
        assertEquals("plugin_special_action", dynamicTool!!.name)

        // Uninstall package and verify cleanup
        val uninstalled = HarkPkgManager.uninstallPackage("plugin.dynamic")
        assertTrue(uninstalled)
        assertFalse(AgentTools.makeAgentTools().any { it.name == "plugin_special_action" })
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 支柱三业务测试：长程记忆 RAG 分块与混合召回
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun testPillar3_AgentToolsContainsRagTools() {
        val tools = AgentTools.makeAgentTools()
        assertNotNull(tools.find { it.name == RagTools.TOOL_DOC_INDEX })
        assertNotNull(tools.find { it.name == RagTools.TOOL_DOC_QUERY })
    }

    @Test
    fun testPillar3_HybridRAGIndexingAndRRFRetrievalFlow() {
        val docText = """
            # OpenMinis Developer Manual
            
            ## Networking Subsystem
            The networking subsystem uses OkHttp and Retrofit for cloud LLM communication.
            
            ## Device Automation Engine
            The DeviceActionDispatcher bridges Shizuku and AccessibilityService.
            It provides fast ADB input injection without requiring root access.
            
            ## Virtual Display Technology
            Shower creates an offscreen virtual display to allow silent background app execution.
            Users can continue using their phone foreground without interruption.
        """.trimIndent()

        // 1. Chunking
        val chunks = DocumentChunker.chunkMarkdown("docs/manual.md", docText)
        assertTrue("Should produce multiple semantic chunks", chunks.size >= 3)

        // 2. Index into HybridRetrievalEngine
        val engine = RagTools.getEngineForSession("v125-test-session")
        engine.clear()
        engine.addChunks(chunks)

        // 3. Search exact technical term (BM25 path)
        val query1Results = engine.search("Shizuku AccessibilityService", topK = 3)
        assertTrue(query1Results.isNotEmpty())
        assertTrue(query1Results.first().chunk.content.contains("DeviceActionDispatcher"))
        assertTrue(query1Results.first().keywordScore > 0f)
        assertTrue(query1Results.first().rrfScore > 0f)

        // 4. Search conceptual question (Shower silent background)
        val query2Results = engine.search("silent background app execution without interruption", topK = 3)
        assertTrue(query2Results.isNotEmpty())
        assertTrue(query2Results.first().chunk.content.contains("Shower"))

        // 5. Memory guard test: ensure MAX_CHUNKS_PER_ENGINE caps index size
        val dummyChunks = (1..50).map { i ->
            com.openminis.app.rag.chunking.DocumentChunk(
                id = "dummy-$i",
                filePath = "dummy.txt",
                content = "Dummy content $i",
                breadcrumb = "Dummy",
                startLine = i,
                endLine = i,
                tokenEstimate = 5,
            )
        }
        engine.addChunks(dummyChunks)
        assertTrue(engine.size() <= HybridRetrievalEngine.MAX_CHUNKS_PER_ENGINE)
    }
}
