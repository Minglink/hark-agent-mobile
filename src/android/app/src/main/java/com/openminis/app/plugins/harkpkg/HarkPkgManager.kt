package com.openminis.app.plugins.harkpkg

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class HarkPkgManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val minAppVersion: String = "1.0.0",
    val permissions: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
) {
    companion object {
        fun fromJson(jsonStr: String): HarkPkgManifest {
            val json = JSONObject(jsonStr)
            val perms = mutableListOf<String>()
            val permArray = json.optJSONArray("permissions")
            if (permArray != null) {
                for (i in 0 until permArray.length()) {
                    perms.add(permArray.getString(i))
                }
            }
            return HarkPkgManifest(
                id = json.getString("id"),
                name = json.optString("name", json.getString("id")),
                version = json.optString("version", "1.0.0"),
                description = json.optString("description", ""),
                author = json.optString("author", ""),
                minAppVersion = json.optString("min_app_version", "1.0.0"),
                permissions = perms,
                enabledByDefault = json.optBoolean("enabled_by_default", true),
            )
        }
    }
}

data class HarkPkgPackage(
    val manifest: HarkPkgManifest,
    val rootDir: File,
    var isEnabled: Boolean = true,
    val systemPromptAddons: List<String> = emptyList(),
    val dynamicTools: List<AgentToolDefinition> = emptyList(),
    val scriptFiles: List<File> = emptyList(),
)

object HarkPkgManager {
    private const val TAG = "HarkPkgManager"
    private const val MANIFEST_FILE = "manifest.json"
    private const val DYNAMIC_TOOLS_FILE = "tools/dynamic_tools.json"

    private val installedPackages = ConcurrentHashMap<String, HarkPkgPackage>()

    fun defaultPluginsDir(context: Context): File = File(context.filesDir, "harkpkg/installed")

    fun getInstalledPackages(): List<HarkPkgPackage> = installedPackages.values.toList()

    fun getPackage(id: String): HarkPkgPackage? = installedPackages[id]

    @Synchronized
    fun setPackageEnabled(id: String, enabled: Boolean) {
        installedPackages[id]?.let { it.isEnabled = enabled }
    }

    @Synchronized
    fun uninstallPackage(id: String): Boolean {
        val pkg = installedPackages.remove(id) ?: return false
        return try {
            pkg.rootDir.deleteRecursively()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Installs a .harkpkg ZIP stream into targetDir.
     * Prevents Zip Slip vulnerabilities during extraction.
     */
    @Synchronized
    fun installPackage(inputStream: InputStream, targetDir: File): HarkPkgPackage {
        if (!targetDir.exists()) targetDir.mkdirs()

        // Unpack ZIP to temporary staging folder
        val tempExtractDir = File.createTempFile("harkpkg_stage_", "", targetDir.parentFile)
        tempExtractDir.delete()
        tempExtractDir.mkdirs()

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryFile = File(tempExtractDir, entry.name)
                    // Security: Zip Slip path traversal check
                    if (!entryFile.canonicalPath.startsWith(tempExtractDir.canonicalPath)) {
                        throw SecurityException("Malicious zip entry attempting path traversal: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Validate manifest.json exists
            val manifestFile = File(tempExtractDir, MANIFEST_FILE)
            if (!manifestFile.exists()) {
                throw IllegalArgumentException("Invalid .harkpkg archive: Missing $MANIFEST_FILE")
            }

            val manifest = HarkPkgManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
            val finalPkgDir = File(targetDir, manifest.id)
            if (finalPkgDir.exists()) {
                finalPkgDir.deleteRecursively()
            }
            tempExtractDir.renameTo(finalPkgDir)

            val pkg = loadPackageFromDir(finalPkgDir)
            installedPackages[manifest.id] = pkg
            AppLogger.info(TAG, "Successfully installed HarkPkg: ${manifest.id} (v${manifest.version})")
            return pkg
        } finally {
            if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
        }
    }

    /**
     * Scans and loads all installed packages in plugins directory.
     */
    @Synchronized
    fun scanAndLoadAll(pluginsDir: File): List<HarkPkgPackage> {
        if (!pluginsDir.exists()) pluginsDir.mkdirs()
        installedPackages.clear()

        val subdirs = pluginsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in subdirs) {
            val manifestFile = File(dir, MANIFEST_FILE)
            if (manifestFile.exists()) {
                try {
                    val pkg = loadPackageFromDir(dir)
                    installedPackages[pkg.manifest.id] = pkg
                    AppLogger.info(TAG, "Loaded HarkPkg: ${pkg.manifest.id} (${pkg.manifest.version})")
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "Failed to load HarkPkg from ${dir.name}: ${t.message}")
                }
            }
        }
        return getInstalledPackages()
    }

    private fun loadPackageFromDir(dir: File): HarkPkgPackage {
        val manifestFile = File(dir, MANIFEST_FILE)
        val manifest = HarkPkgManifest.fromJson(manifestFile.readText(Charsets.UTF_8))

        // 1. Load system prompt addons from prompt/*.md
        val promptDir = File(dir, "prompt")
        val promptAddons = mutableListOf<String>()
        if (promptDir.exists() && promptDir.isDirectory) {
            promptDir.walkTopDown().filter { it.isFile && it.extension.lowercase() == "md" }.forEach { file ->
                promptAddons.add(file.readText(Charsets.UTF_8).trim())
            }
        }

        // 2. Load dynamic tools definition from tools/dynamic_tools.json
        val toolsJsonFile = File(dir, DYNAMIC_TOOLS_FILE)
        val dynamicTools = mutableListOf<AgentToolDefinition>()
        if (toolsJsonFile.exists()) {
            try {
                val json = JSONObject(toolsJsonFile.readText(Charsets.UTF_8))
                val toolsArray = json.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until toolsArray.length()) {
                    val tObj = toolsArray.getJSONObject(i)
                    val name = tObj.getString("name")
                    val desc = tObj.optString("description", "")
                    val paramsObj = tObj.optJSONObject("parameters")
                    val paramsMap = mutableMapOf<String, AgentToolParam>()
                    val reqList = mutableListOf<String>()

                    if (paramsObj != null) {
                        val props = paramsObj.optJSONObject("properties")
                        props?.keys()?.forEach { key ->
                            val p = props.getJSONObject(key)
                            paramsMap[key] = AgentToolParam(
                                type = p.optString("type", "string"),
                                description = p.optString("description", ""),
                            )
                        }
                        val reqArray = paramsObj.optJSONArray("required")
                        if (reqArray != null) {
                            for (r in 0 until reqArray.length()) reqList.add(reqArray.getString(r))
                        }
                    }

                    dynamicTools.add(
                        AgentToolDefinition(
                            name = name,
                            description = desc,
                            parameters = paramsMap,
                            required = reqList,
                        )
                    )
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Failed to parse dynamic_tools.json in ${manifest.id}: ${t.message}")
            }
        }

        // 3. Collect script files in tools/scripts
        val scriptsDir = File(dir, "tools/scripts")
        val scripts = if (scriptsDir.exists() && scriptsDir.isDirectory) {
            scriptsDir.listFiles { f -> f.isFile }?.toList() ?: emptyList()
        } else emptyList()

        return HarkPkgPackage(
            manifest = manifest,
            rootDir = dir,
            isEnabled = manifest.enabledByDefault,
            systemPromptAddons = promptAddons,
            dynamicTools = dynamicTools,
            scriptFiles = scripts,
        )
    }

    /**
     * Collects all active system prompt addons across enabled packages.
     */
    fun activeSystemPromptAddons(): List<String> {
        return installedPackages.values
            .filter { it.isEnabled }
            .flatMap { it.systemPromptAddons }
    }

    /**
     * Collects all active dynamic tools across enabled packages.
     */
    fun activeDynamicTools(): List<AgentToolDefinition> {
        return installedPackages.values
            .filter { it.isEnabled }
            .flatMap { it.dynamicTools }
    }
}
