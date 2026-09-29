package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.PiInstallStatus
import com.yugahashimoto.andcode.runtime.local.PiUiState

/**
 * Install, status, and control cards for the standalone Pi coding agent.
 */
@Composable
fun PiCard(
    pi: PiUiState,
    onInstall: () -> Unit,
    onRestart: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
) {
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val install = pi.install) {
            is PiInstallStatus.Installing -> {
                Text(
                    install.step ?: stringResource(R.string.pi_installing),
                    style = MaterialTheme.typography.bodySmall,
                )
                val progress = install.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is PiInstallStatus.Failed -> {
                SelectionContainer {
                    Text(
                        install.message ?: stringResource(R.string.pi_error_install_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.pi_retry_install_button))
                }
            }
            else -> {
                if (pi.installed) {
                    Text(
                        text = stringResource(R.string.setup_agent_pi_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onRestart != null || onStop != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (onRestart != null) {
                                OutlinedButton(
                                    onClick = onRestart,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(stringResource(R.string.runtime_restart_button))
                                }
                            }
                            if (onStop != null) {
                                OutlinedButton(
                                    onClick = onStop,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(stringResource(R.string.runtime_stop_button))
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.pi_needs_runtime_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.pi_install_button))
                    }
                }
            }
        }
    }
}
