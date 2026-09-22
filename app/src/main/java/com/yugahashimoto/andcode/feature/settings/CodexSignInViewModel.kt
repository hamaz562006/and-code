package com.yugahashimoto.andcode.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.runtime.local.CodexModels
import com.yugahashimoto.andcode.runtime.local.CodexTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the same provider sign-in dialog OpenCode's providers use, for Codex.
 *
 * The steps are those of [SettingsViewModel]'s provider flow - pick a method, authorize, and for an
 * "auto" OAuth method poll until the browser round trip completes - because the dialog and the
 * [CodexTarget] methods it calls follow the same contract. It is a separate class only because
 * [SettingsViewModel] is bound to OpenCode's target and its provider catalogue.
 */
class CodexSignInViewModel(
    private val target: CodexTarget,
    /** Called once an account is signed in, so the caller can re-read the sign-in state. */
    private val onSignedIn: () -> Unit,
) : ViewModel() {
    private val mutableDialog = MutableStateFlow<ProviderAuthDialogState?>(null)
    val dialog: StateFlow<ProviderAuthDialogState?> = mutableDialog.asStateFlow()

    private var job: Job? = null

    fun open() {
        if (mutableDialog.value != null) return
        viewModelScope.launch {
            val methods = runCatching { target.providerAuthMethods()[CodexModels.PROVIDER_ID] }.getOrNull().orEmpty()
            mutableDialog.value =
                ProviderAuthDialogState(
                    providerId = CodexModels.PROVIDER_ID,
                    providerName = PROVIDER_NAME,
                    methods = methods,
                )
        }
    }

    fun selectMethod(methodIndex: Int) {
        val current = mutableDialog.value ?: return
        val method = current.methods.getOrNull(methodIndex) ?: return
        mutableDialog.value =
            current.copy(
                methodIndex = methodIndex,
                inputs = emptyMap(),
                apiKey = "",
                authorization = null,
                isSubmitting = false,
                failed = false,
                error = null,
            )
        if (method.type == "oauth" && method.prompts.isEmpty()) submit()
    }

    fun updateApiKey(value: String) {
        mutableDialog.update { it?.copy(apiKey = value, failed = false, error = null) }
    }

    fun submit() {
        val dialog = mutableDialog.value ?: return
        val methodIndex = dialog.methodIndex ?: return
        val method = dialog.selectedMethod ?: return
        if (dialog.isSubmitting || job?.isActive == true) return
        job =
            viewModelScope.launch {
                when (method.type) {
                    "api" -> submitApiKey(dialog)
                    "oauth" -> submitOAuth(dialog, methodIndex)
                }
            }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        mutableDialog.value = null
        // A browser sign-in the user walked away from would otherwise keep Codex's local callback
        // listener bound until the next attempt.
        viewModelScope.launch(NonCancellable) { runCatching { target.cancelSignIn() } }
    }

    /**
     * The dialog can go away without Cancel - the task swiped off, the screen left mid-sign-in - and
     * a sign-in nothing polls any more would keep Codex's callback listener bound and
     * CodexKeepAliveService running. viewModelScope is already cancelled here, so this runs on a
     * scope of its own.
     */
    override fun onCleared() {
        if (mutableDialog.value?.authorization != null) {
            CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { target.cancelSignIn() } }
        }
        super.onCleared()
    }

    private suspend fun submitApiKey(dialog: ProviderAuthDialogState) {
        val apiKey = dialog.apiKey.trim()
        if (apiKey.isEmpty()) return
        mutableDialog.value = dialog.copy(isSubmitting = true, failed = false, error = null)
        runCatching { target.setProviderApiKey(dialog.providerId, apiKey, dialog.inputs) }
            .onSuccess { accepted -> if (accepted) finish() else fail(null) }
            .onFailure { fail(it.message) }
    }

    private suspend fun submitOAuth(
        dialog: ProviderAuthDialogState,
        methodIndex: Int,
    ) {
        mutableDialog.value = dialog.copy(isSubmitting = true, failed = false, error = null)
        val authorization =
            runCatching { target.authorizeProvider(dialog.providerId, methodIndex, dialog.inputs) }
                .getOrElse { return fail(it.message) }
        mutableDialog.value = dialog.copy(authorization = authorization, isSubmitting = true, failed = false, error = null)

        val deadline = System.currentTimeMillis() + OAUTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val completed =
                runCatching { target.completeProviderOAuth(dialog.providerId, methodIndex, null) }
                    .getOrElse { return fail(it.message) }
            if (completed) return finish()
            delay(OAUTH_POLL_MS)
        }
        // Timed out: release the browser sign-in too, or the keep-alive service would outlive it.
        runCatching { target.cancelSignIn() }
        fail(null)
    }

    private fun finish() {
        job = null
        mutableDialog.value = null
        onSignedIn()
    }

    private fun fail(message: String?) {
        job = null
        mutableDialog.update { it?.copy(isSubmitting = false, failed = true, error = message?.takeIf(String::isNotBlank)) }
    }

    private companion object {
        const val PROVIDER_NAME = "OpenAI (Codex)"
        const val OAUTH_TIMEOUT_MS = 6 * 60 * 1000L
        const val OAUTH_POLL_MS = 1000L
    }
}
