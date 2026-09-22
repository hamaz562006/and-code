package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `listJson` is verbatim `codex mcp list --json` output (codex-cli 0.142.5) for one stdio and one HTTP server. */
class CodexMcpTest {
    private val listJson =
        """
        [
          {"name":"fs","enabled":true,"disabled_reason":null,
           "transport":{"type":"stdio","command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/workspace"],
                        "env":null,"env_vars":[],"cwd":null},
           "startup_timeout_sec":null,"tool_timeout_sec":null,"auth_status":"unsupported"},
          {"name":"web","enabled":true,"disabled_reason":null,
           "transport":{"type":"streamable_http","url":"https://example.com/mcp","bearer_token_env_var":null,
                        "http_headers":null,"env_http_headers":null},
           "startup_timeout_sec":null,"tool_timeout_sec":null,"auth_status":"unsupported"}
        ]
        """.trimIndent()

    @Test
    fun `parses a stdio server with its full command line`() {
        val fs = CodexMcp.parseList(listJson).first { it.name == "fs" }

        assertEquals("local", fs.type)
        assertEquals("npx -y @modelcontextprotocol/server-filesystem /workspace", fs.command)
        assertNull(fs.url)
        assertEquals("enabled", fs.status)
    }

    @Test
    fun `parses a streamable HTTP server as remote`() {
        val web = CodexMcp.parseList(listJson).first { it.name == "web" }

        assertEquals("remote", web.type)
        assertEquals("https://example.com/mcp", web.url)
        assertNull(web.command)
    }

    @Test
    fun `an empty list reads as no servers`() {
        assertTrue(CodexMcp.parseList("[]").isEmpty())
    }

    @Test
    fun `output that is not a JSON array reads as no servers`() {
        assertTrue(CodexMcp.parseList("No MCP servers configured yet.").isEmpty())
        assertTrue(CodexMcp.parseList("").isEmpty())
    }

    @Test
    fun `a disabled server reports its reason`() {
        val servers =
            CodexMcp.parseList(
                """[{"name":"x","enabled":false,"disabled_reason":"requirements",
                    "transport":{"type":"stdio","command":"x","args":[]}}]""",
            )

        assertEquals("disabled", servers.single().status)
        assertEquals("requirements", servers.single().error)
    }

    @Test
    fun `add uses --url for a remote server and quotes the name`() {
        assertEquals(
            "/usr/local/bin/codex mcp add 'my server' --url 'https://example.com/mcp' 2>&1",
            CodexMcp.addScript("my server", "https://example.com/mcp", null),
        )
    }

    @Test
    fun `add passes a local command line through after --`() {
        assertEquals(
            "/usr/local/bin/codex mcp add 'fs' -- npx -y server /workspace 2>&1",
            CodexMcp.addScript("fs", null, "npx -y server /workspace"),
        )
    }

    @Test
    fun `add needs a URL or a command`() {
        assertNull(CodexMcp.addScript("fs", null, " "))
    }

    @Test
    fun `remove quotes a name containing a single quote`() {
        assertEquals("/usr/local/bin/codex mcp remove 'it'\\''s' 2>&1", CodexMcp.removeScript("it's"))
    }
}
