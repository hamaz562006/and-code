package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers how each ChatGPT browser sign-in ended.
 *
 * `account/login/start` returns the URL to open and a `loginId` straight away, and the result
 * arrives later as an `account/login/completed` notification once the browser has been through
 * Codex's local callback. The UI polls for it (the same "auto" OAuth shape OpenCode's providers use),
 * so the outcome has to be kept somewhere between the notification and the next poll.
 */
class CodexLoginTracker {
    sealed interface Outcome {
        data object Succeeded : Outcome

        /** [message] is null when Codex gave no reason, so the UI can show its own translated default. */
        data class Failed(val message: String?) : Outcome
    }

    private val outcomes = ConcurrentHashMap<String, Outcome>()

    /** The sign-in the UI is currently waiting on, for a completion that names no id. */
    @Volatile var activeLoginId: String? = null
        private set

    fun begin(loginId: String) {
        activeLoginId = loginId
    }

    /** Records an `account/login/completed` notification body (`success`, `loginId`, `error`). */
    fun onCompleted(body: JsonObject) {
        val loginId = (body["loginId"] as? JsonPrimitive)?.contentOrNull ?: activeLoginId ?: return
        val success = (body["success"] as? JsonPrimitive)?.booleanOrNull == true
        val error = (body["error"] as? JsonPrimitive)?.contentOrNull
        outcomes[loginId] = if (success) Outcome.Succeeded else Outcome.Failed(error?.takeIf(String::isNotBlank))
    }

    /** The outcome for [loginId], or null while the browser round trip is still in progress. */
    fun outcome(loginId: String): Outcome? = outcomes[loginId]

    /** True from [begin] until the completion notification for the active sign-in arrives. */
    val pending: Boolean
        get() = activeLoginId?.let { !outcomes.containsKey(it) } == true

    fun clear() {
        outcomes.clear()
        activeLoginId = null
    }

    fun forget(loginId: String) {
        outcomes.remove(loginId)
        if (activeLoginId == loginId) activeLoginId = null
    }
}
