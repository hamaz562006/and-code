package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

/**
 * GitHub connection for workspaces and settings: Device Flow and personal access token,
 * plus import/clone when connected (AndroidHarness-style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubSettingsScreen(
    state: SettingsUiState,
    onConnect: () -> Unit,
    onConnectPat: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenVerification: (String) -> Unit,
    onImportFromGithub: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    var showPatForm by remember { mutableStateOf(false) }
    var patToken by remember { mutableStateOf("") }

    LaunchedEffect(state.githubVerificationUrl) {
        state.githubVerificationUrl?.let(onOpenVerification)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.github_screen_title_short)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.github_workspace_header),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— Connect section ——
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.github_connect_section_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (state.githubLogin != null) {
                            stringResource(R.string.github_signed_in_as, state.githubLogin!!)
                        } else {
                            stringResource(R.string.github_connect_section_body)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.githubMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (!state.githubConfigured && state.githubLogin == null) {
                        Text(
                            stringResource(R.string.github_client_id_missing_pat_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    state.githubUserCode?.let { code ->
                        GithubDeviceCodeCard(code, state.githubVerificationUrl, onOpenVerification)
                    }

                    if (state.githubLogin == null) {
                        if (!showPatForm) {
                            Button(
                                onClick = onConnect,
                                enabled = state.githubConfigured && !state.githubPolling,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (state.githubPolling) {
                                        stringResource(R.string.github_waiting_for_authorization)
                                    } else {
                                        stringResource(R.string.github_sign_in_with_github)
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    showPatForm = true
                                    patToken = ""
                                },
                                enabled = !state.githubPolling,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.github_use_pat))
                            }
                        } else {
                            Text(
                                stringResource(R.string.github_pat_instructions),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = patToken,
                                onValueChange = { patToken = it },
                                label = { Text(stringResource(R.string.github_pat_field_label)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { onConnectPat(patToken) },
                                enabled = patToken.isNotBlank() && !state.githubPolling,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.github_pat_verify_connect))
                            }
                            TextButton(
                                onClick = {
                                    showPatForm = false
                                    patToken = ""
                                },
                                enabled = !state.githubPolling,
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.github_disconnect))
                        }
                    }
                }
            }

            // —— Repositories section (connected or still show import entry) ——
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.github_repositories_section_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (onImportFromGithub != null) {
                        Button(
                            onClick = onImportFromGithub,
                            enabled = state.githubLogin != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.github_import_from_github))
                        }
                    }
                    Text(
                        if (state.githubLogin == null) {
                            stringResource(R.string.github_import_requires_connect)
                        } else {
                            stringResource(R.string.github_import_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Icon(
                Icons.Default.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.github_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
