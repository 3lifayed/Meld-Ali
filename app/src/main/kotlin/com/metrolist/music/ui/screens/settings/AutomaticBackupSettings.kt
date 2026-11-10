package com.metrolist.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.backup.AutomaticBackup
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AutomaticBackupSettings() {
    val context = LocalContext.current
    val backup = remember(context) { AutomaticBackup(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val updates = remember(backup) { backup.observe() }
    val settings by updates.collectAsState(initial = backup.read())
    var chooseFrequency by remember { mutableStateOf(false) }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { backup.selectFolder(folder) } }
                if (result.isFailure) {
                    Toast.makeText(context, R.string.automatic_backup_folder_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        items = listOf(
            Material3SettingsItem(
                title = { Text(stringResource(R.string.automatic_backup)) },
                description = { Text(stringResource(R.string.automatic_backup_description)) },
                trailingContent = {
                    Switch(checked = settings.enabled, onCheckedChange = null, enabled = settings.folder != null)
                },
                enabled = settings.folder != null,
                onClick = { backup.setEnabled(!settings.enabled) },
            ),
            Material3SettingsItem(
                title = { Text(stringResource(R.string.automatic_backup_location)) },
                description = {
                    Text(settings.folderName ?: stringResource(R.string.automatic_backup_choose_folder))
                },
                onClick = { chooseFolder.launch(settings.folder) },
            ),
            Material3SettingsItem(
                title = { Text(stringResource(R.string.automatic_backup_frequency)) },
                description = { Text(stringResource(settings.frequency.label)) },
                onClick = { chooseFrequency = true },
            ),
            Material3SettingsItem(
                title = { Text(stringResource(R.string.automatic_backup_now)) },
                description = {
                    val status = when {
                        settings.lastSuccess == 0L -> stringResource(R.string.automatic_backup_no_success)
                        else -> stringResource(
                            R.string.automatic_backup_last_success,
                            DateFormat.getDateTimeInstance().format(Date(settings.lastSuccess)),
                        )
                    }
                    Text(
                        if (settings.failed) {
                            stringResource(R.string.automatic_backup_failed, status)
                        } else {
                            status
                        },
                    )
                },
                enabled = settings.folder != null,
                onClick = { backup.runNow() },
            ),
        ),
    )

    if (chooseFrequency) {
        DefaultDialog(
            onDismiss = { chooseFrequency = false },
            title = { Text(stringResource(R.string.automatic_backup_frequency)) },
        ) {
            Material3SettingsGroup(
                items = AutomaticBackup.Frequency.entries.map { frequency ->
                    Material3SettingsItem(
                        title = { Text(stringResource(frequency.label)) },
                        isHighlighted = settings.frequency == frequency,
                        onClick = {
                            backup.setFrequency(frequency)
                            chooseFrequency = false
                        },
                    )
                },
            )
        }
    }
}

private val AutomaticBackup.Frequency.label: Int
    get() = when (this) {
        AutomaticBackup.Frequency.DAILY -> R.string.automatic_backup_daily
        AutomaticBackup.Frequency.EVERY_THREE_DAYS -> R.string.automatic_backup_every_three_days
        AutomaticBackup.Frequency.WEEKLY -> R.string.automatic_backup_weekly
    }
