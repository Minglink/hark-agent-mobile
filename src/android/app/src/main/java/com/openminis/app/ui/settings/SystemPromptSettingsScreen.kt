package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SystemPromptFile
import com.openminis.app.agent.SystemPromptMode
import com.openminis.app.agent.SystemPromptStore
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-system-md] Settings page for the highest-priority custom system
 * prompt (SYSTEM.md). Mirrors [SoulSettingsScreen]'s layout language —
 * SettingsScaffold + grouped SettingsSections, Save in the app bar,
 * Restore Default with a confirmation dialog, unsaved-changes guard —
 * with three deltas that match the different contract:
 *
 *   - A Mode picker (Off / Top / Bottom) instead of identity fields.
 *   - An "Import from file…" action (ActivityResultContracts.GetContent)
 *     so a full prompt authored elsewhere can be loaded in one tap.
 *   - NO content filtering anywhere: the body is saved and injected
 *     verbatim. Only the 100k-character foolproofing cap applies.
 */
@Composable
fun SystemPromptSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(SystemPromptMode.OFF) }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    // Snapshot of what disk held at load time — dirty check compares by
    // value (same pattern as SoulSettingsScreen's baseline).
    var baseline by remember { mutableStateOf<SystemPromptFile?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Import: pick a UTF-8 text file from device storage and load it into
    // the editor. Over-limit files are rejected with an error rather than
    // silently truncated (truncation would corrupt a prompt the user
    // cannot see being cut).
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrNull()
            }
            when {
                text == null ->
                    importError = context.getString(R.string.sysprompt_import_error_unreadable)
                text.trim().length > SystemPromptStore.BODY_CHAR_LIMIT ->
                    importError = context.getString(R.string.sysprompt_import_error_too_large)
                else -> body = text
            }
        }
    }

    // Initial load + (defensive) ensureExists — same cadence as Soul.
    LaunchedEffect(Unit) {
        val parsed = withContext(Dispatchers.IO) {
            SystemPromptStore.ensureExists(context)
            SystemPromptStore.load(context) ?: SystemPromptStore.DEFAULT
        }
        mode = parsed.mode
        body = parsed.body
        baseline = parsed
        loaded = true
    }

    val currentFile = SystemPromptFile(mode = mode, body = body)
    val isDirty = loaded && baseline != null && currentFile != baseline
    val overLimit = SystemPromptStore.isOverLimit(body)

    val save: () -> Unit = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SystemPromptStore.save(context, currentFile) }
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: "save failed"
            }
        }
        Unit
    }

    // Unsaved-changes guard — one lambda shared by the app-bar back arrow
    // and the system back gesture (same as Soul).
    val attemptBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDirty) { showDiscardDialog = true }

    SettingsScaffold(
        title = stringResource(R.string.sysprompt_settings_title),
        onBack = attemptBack,
        actions = {
            MinisTextButton(
                onClick = save,
                enabled = loaded && isDirty && !overLimit,
            ) { Text(stringResource(R.string.soul_save)) }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.sysprompt_section_mode),
            footer = stringResource(R.string.sysprompt_mode_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                ModePicker(mode = mode, onModeChange = { mode = it })
                if (mode == SystemPromptMode.OFF) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sysprompt_section_status_note),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.sysprompt_section_prompt),
            footer = stringResource(R.string.sysprompt_prompt_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = { Text(stringResource(R.string.sysprompt_body_placeholder)) },
                )
                Spacer(Modifier.height(6.dp))
                val length = body.trim().length
                Text(
                    text = if (overLimit) {
                        stringResource(R.string.sysprompt_over_limit, SystemPromptStore.BODY_CHAR_LIMIT)
                    } else {
                        stringResource(R.string.sysprompt_count, length, SystemPromptStore.BODY_CHAR_LIMIT)
                    },
                    fontSize = 12.sp,
                    color = if (overLimit) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                MinisOutlinedButton(
                    onClick = { importLauncher.launch("text/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sysprompt_import)) }
            }
        }

        SettingsSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MinisOutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.soul_restore_default)) }
            }
        }
    }

    // Unsaved-changes dialog — verdict-first wording, same as Soul.
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.sysprompt_discard_confirm_title)) },
            text = { Text(stringResource(R.string.sysprompt_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.sysprompt_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.sysprompt_discard_cancel))
                }
            },
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.sysprompt_restore_confirm_title)) },
            text = { Text(stringResource(R.string.sysprompt_restore_confirm_body)) },
            confirmButton = {
                MinisButton(onClick = {
                    mode = SystemPromptStore.DEFAULT.mode
                    body = SystemPromptStore.DEFAULT.body
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.soul_restore_default)) }
            },
            dismissButton = {
                MinisOutlinedButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    importError?.let { err ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.sysprompt_import_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { importError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }
}

/**
 * Three-way mode selector rendered as a row of filled/outlined buttons —
 * mirrors SoulSettingsScreen's LangPicker so the two prompt pages read as
 * siblings (no dropdown dependency, same 8dp spacing).
 */
@Composable
private fun ModePicker(mode: SystemPromptMode, onModeChange: (SystemPromptMode) -> Unit) {
    val options = listOf(
        SystemPromptMode.OFF to stringResource(R.string.sysprompt_mode_off),
        SystemPromptMode.PREPEND to stringResource(R.string.sysprompt_mode_top),
        SystemPromptMode.SUFFIX to stringResource(R.string.sysprompt_mode_bottom),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            if (key == mode) {
                MinisButton(
                    onClick = { onModeChange(key) },
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            } else {
                MinisOutlinedButton(
                    onClick = { onModeChange(key) },
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
        }
    }
}
