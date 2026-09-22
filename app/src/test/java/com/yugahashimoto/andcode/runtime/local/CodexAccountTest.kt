package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both bodies are real `account/read` results from codex-cli 0.155.1 (the email redacted). */
class CodexAccountTest {
    private fun result(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a null account is signed out`() {
        assertFalse(hasSignedInAccount(result("""{"account":null,"requiresOpenaiAuth":true}""")))
    }

    @Test
    fun `a ChatGPT account is signed in`() {
        assertTrue(
            hasSignedInAccount(
                result("""{"account":{"type":"chatgpt","email":"user@example.com","planType":"plus"},"requiresOpenaiAuth":true}"""),
            ),
        )
    }

    @Test
    fun `an API-key account is signed in`() {
        assertTrue(hasSignedInAccount(result("""{"account":{"type":"apiKey"},"requiresOpenaiAuth":true}""")))
    }

    @Test
    fun `a result without an account key is signed out`() {
        assertFalse(hasSignedInAccount(result("""{"requiresOpenaiAuth":true}""")))
    }
}
