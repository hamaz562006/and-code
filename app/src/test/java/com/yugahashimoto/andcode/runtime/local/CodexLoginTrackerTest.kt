package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bodies follow `AccountLoginCompletedNotification` from `codex app-server generate-json-schema` (0.142.5). */
class CodexLoginTrackerTest {
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `is pending until the completion notification arrives`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }

        assertNull(tracker.outcome("login-1"))
    }

    @Test
    fun `records a successful sign-in by login id`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }

        tracker.onCompleted(body("""{"success":true,"loginId":"login-1","error":null}"""))

        assertEquals(CodexLoginTracker.Outcome.Succeeded, tracker.outcome("login-1"))
    }

    @Test
    fun `records the error of a failed sign-in`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }

        tracker.onCompleted(body("""{"success":false,"loginId":"login-1","error":"access_denied"}"""))

        assertEquals(CodexLoginTracker.Outcome.Failed("access_denied"), tracker.outcome("login-1"))
    }

    @Test
    fun `leaves the message empty when a failure carries no error`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }

        tracker.onCompleted(body("""{"success":false,"loginId":"login-1"}"""))

        assertEquals(CodexLoginTracker.Outcome.Failed(null), tracker.outcome("login-1"))
    }

    @Test
    fun `attributes a completion that names no id to the active sign-in`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }

        tracker.onCompleted(body("""{"success":true,"loginId":null}"""))

        assertEquals(CodexLoginTracker.Outcome.Succeeded, tracker.outcome("login-1"))
    }

    @Test
    fun `ignores another sign-in's completion`() {
        val tracker = CodexLoginTracker().apply { begin("login-2") }

        tracker.onCompleted(body("""{"success":true,"loginId":"login-1"}"""))

        assertNull(tracker.outcome("login-2"))
    }

    @Test
    fun `is pending only until the active sign-in completes`() {
        val tracker = CodexLoginTracker()
        assertEquals(false, tracker.pending)

        tracker.begin("login-1")
        assertEquals(true, tracker.pending)

        tracker.onCompleted(body("""{"success":true,"loginId":"login-1"}"""))
        assertEquals(false, tracker.pending)
    }

    @Test
    fun `clearing drops a pending sign-in`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }

        tracker.clear()

        assertEquals(false, tracker.pending)
        assertNull(tracker.outcome("login-1"))
    }

    @Test
    fun `forgetting a sign-in clears its outcome`() {
        val tracker = CodexLoginTracker().apply { begin("login-1") }
        tracker.onCompleted(body("""{"success":true,"loginId":"login-1"}"""))

        tracker.forget("login-1")

        assertNull(tracker.outcome("login-1"))
    }
}
