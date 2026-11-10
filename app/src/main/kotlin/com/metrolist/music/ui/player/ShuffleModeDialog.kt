package com.metrolist.music.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.playback.ShuffleStyle

@Composable
internal fun shuffleStyleLabel(style: ShuffleStyle): String = stringResource(
    when (style) {
        ShuffleStyle.RANDOM -> R.string.shuffle_random
        ShuffleStyle.BETTER -> R.string.shuffle_better
        ShuffleStyle.DISCOVERY -> R.string.shuffle_discovery
    },
)

@Composable
internal fun ShuffleModeDialog(
    enabled: Boolean,
    style: ShuffleStyle,
    loading: Boolean,
    error: Int?,
    onSelect: (ShuffleStyle?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shuffle_mode_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in listOf(null, ShuffleStyle.RANDOM, ShuffleStyle.BETTER, ShuffleStyle.DISCOVERY)) {
                    val selected = if (option == null) !enabled else enabled && style == option
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(option); onDismiss() },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(if (option == null) stringResource(R.string.shuffle_disabled) else shuffleStyleLabel(option))
                            Text(
                                stringResource(when (option) {
                                    null -> R.string.shuffle_disabled_desc
                                    ShuffleStyle.RANDOM -> R.string.shuffle_random_desc
                                    ShuffleStyle.BETTER -> R.string.shuffle_better_desc
                                    ShuffleStyle.DISCOVERY -> R.string.shuffle_discovery_desc
                                }),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (enabled && style == ShuffleStyle.DISCOVERY && (loading || error != null)) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(if (loading) R.string.shuffle_discovery_loading else error ?: R.string.shuffle_discovery_unavailable))
                    if (error != null && !loading) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
