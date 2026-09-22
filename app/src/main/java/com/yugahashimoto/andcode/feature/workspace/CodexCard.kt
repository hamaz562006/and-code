package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.CodexInstallStatus
import com.yugahashimoto.andcode.runtime.local.CodexUiState

/**
 * Install and sign-in controls for the Android-local Codex agent.
 *
 * Every stage says something concrete - installing, why an install failed, whether an account is
 * signed in - so a failed download never looks like a button that did nothing.
 */
@Composable
fun CodexCard(
    codex: CodexUiState,
    onInstall: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val install = codex.install) {
            CodexInstallStatus.Installing -> {
                Text(stringResource(R.string.codex_installing), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is CodexInstallStatus.Failed -> {
                SelectionContainer {
                    Text(
                        install.message ?: stringResource(R.string.codex_error_install_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                InstallButton(R.string.codex_retry_install_button, onInstall)
            }
            CodexInstallStatus.Idle ->
                if (codex.installed) {
                    InstalledSection(codex, onSignIn, onSignOut)
                } else {
                    Text(
                        text = stringResource(R.string.codex_needs_runtime_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InstallButton(R.string.codex_install_button, onInstall)
                }
        }
    }
}

@Composable
private fun InstallButton(
    labelRes: Int,
    onInstall: () -> Unit,
) {
    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Build, contentDescription = null)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(stringResource(labelRes))
    }
}

@Composable
private fun InstalledSection(
    codex: CodexUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (codex.signedIn) {
        Text(
            text = stringResource(R.string.codex_status_signed_in),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codex_sign_out_button))
        }
        return
    }
    Text(
        text = stringResource(R.string.codex_status_signed_out),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.codex_sign_in_button))
    }
}
