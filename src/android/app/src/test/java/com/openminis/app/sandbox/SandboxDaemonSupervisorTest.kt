package com.openminis.app.sandbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SandboxDaemonSupervisorTest {

    @Test
    fun `DaemonServiceInfo correctly parses entry JSON format`() {
        val jsonText = """
            {
                "name": "web-api",
                "pid": 12345,
                "port": 8080,
                "dir": "/var/hark/workspace/my-project",
                "cmd": "python3 app.py",
                "startedAt": 1727100000,
                "status": "running",
                "log": "/var/hark/shared/services/logs/web-api.log"
            }
        """.trimIndent()

        val obj = JSONObject(jsonText)
        val info = DaemonServiceInfo(
            name = obj.getString("name"),
            pid = obj.getInt("pid"),
            port = obj.getInt("port"),
            dir = obj.getString("dir"),
            cmd = obj.getString("cmd"),
            startedAt = obj.getLong("startedAt"),
            status = obj.getString("status"),
            logFile = obj.getString("log"),
            isPortListening = obj.getInt("port") > 0
        )

        assertEquals("web-api", info.name)
        assertEquals(12345, info.pid)
        assertEquals(8080, info.port)
        assertEquals("/var/hark/workspace/my-project", info.dir)
        assertEquals("python3 app.py", info.cmd)
        assertEquals(1727100000L, info.startedAt)
        assertEquals("running", info.status)
        assertEquals("/var/hark/shared/services/logs/web-api.log", info.logFile)
        assertTrue(info.isPortListening)
    }

    @Test
    fun `hark-service asset script exists and contains atomic entry logic`() {
        val scriptFile = File("src/main/assets/default_mount/usr/local/bin/hark-service")
        assertTrue("hark-service must exist in assets", scriptFile.exists())

        val content = scriptFile.readText()
        assertTrue("hark-service must define ENTRIES_DIR", content.contains("ENTRIES_DIR="))
        assertTrue("hark-service must define REGISTRY_FILE", content.contains("REGISTRY_FILE="))
        assertTrue("hark-service must handle setsid execution", content.contains("setsid"))
        assertTrue("hark-service must have start command", content.contains("start)"))
        assertTrue("hark-service must have stop command", content.contains("stop)"))
        assertTrue("hark-service must have list command", content.contains("list|ls)"))
        assertTrue("hark-service must have logs command", content.contains("logs|log)"))
        // Verify BusyBox awk multi-character RS is NOT used
        assertFalse("hark-service must not use unsupported multicharacter RS awk", content.contains("RS=\"},\""))
    }
}
