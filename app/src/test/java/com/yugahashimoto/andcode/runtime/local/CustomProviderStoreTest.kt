package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomProviderStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `encode and decode round trip`() {
        val definitions = listOf(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("acme-large")))
        val encoded = CustomProviderStore.encodeList(definitions)
        assertEquals(definitions, CustomProviderStore.decodeList(encoded))
    }

    @Test
    fun `decode ignores malformed payload`() {
        assertEquals(emptyList<CustomProviderDefinition>(), CustomProviderStore.decodeList("not-json"))
        assertEquals(emptyList<CustomProviderDefinition>(), CustomProviderStore.decodeList(null))
    }

    @Test
    fun `upsert normalizes id, name and models before saving`() {
        val memory = memoryStore()

        memory.store.upsert(
            CustomProviderDefinition(
                id = "  acme  ",
                name = "  ",
                baseUrl = " https://acme.example/v1 ",
                models = listOf(" acme-large ", "", "acme-large", "acme-small"),
            ),
        )

        val saved = memory.store.definitions().single()
        assertEquals("acme", saved.id)
        assertEquals("acme", saved.name)
        assertEquals("https://acme.example/v1", saved.baseUrl)
        assertEquals(listOf("acme-large", "acme-small"), saved.models)
    }

    @Test
    fun `upsert replaces an existing definition with the same id`() {
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://old.example/v1", listOf("m1")))

        memory.store.upsert(CustomProviderDefinition("acme", "Acme Two", "https://new.example/v1", listOf("m2")))

        val saved = memory.store.definitions().single()
        assertEquals("Acme Two", saved.name)
        assertEquals("https://new.example/v1", saved.baseUrl)
        assertEquals(listOf("m2"), saved.models)
    }

    @Test
    fun `upsert rejects a definition with no models`() {
        val memory = memoryStore()

        assertThrows(IllegalArgumentException::class.java) {
            memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", emptyList()))
        }
    }

    @Test
    fun `remove drops the definition by id`() {
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("m1")))

        memory.store.remove("acme")

        assertTrue(memory.store.definitions().isEmpty())
    }

    @Test
    fun `sync writes a provider entry into opencode json`() {
        val rootfs = temp.newFolder("rootfs")
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("acme-large", "acme-small")))

        val configFile = memory.store.syncToRuntime(rootfs)

        assertTrue(configFile.exists())
        val provider = Json.parseToJsonElement(configFile.readText()).jsonObject["provider"]!!.jsonObject["acme"]!!.jsonObject
        assertEquals("@ai-sdk/openai-compatible", provider["npm"]!!.jsonPrimitive.content)
        assertEquals("Acme", provider["name"]!!.jsonPrimitive.content)
        assertEquals("https://acme.example/v1", provider["options"]!!.jsonObject["baseURL"]!!.jsonPrimitive.content)
        val models = provider["models"]!!.jsonObject
        assertTrue("acme-large" in models)
        assertTrue("acme-small" in models)
        assertEquals(setOf("acme"), memory.syncedIds)
    }

    @Test
    fun `sync preserves hand-written provider entries it does not own`() {
        val rootfs = temp.newFolder("preserve-rootfs")
        val configFile =
            File(rootfs, "root/.config/opencode/opencode.json").apply {
                parentFile.mkdirs()
                writeText("""{"provider":{"handwritten":{"npm":"@ai-sdk/openai-compatible","name":"Hand"}}}""")
            }
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("m1")))

        memory.store.syncToRuntime(rootfs)

        val providers = Json.parseToJsonElement(configFile.readText()).jsonObject["provider"]!!.jsonObject
        assertTrue("handwritten" in providers)
        assertTrue("acme" in providers)
    }

    @Test
    fun `sync removes an entry this store previously wrote once its definition is gone`() {
        val rootfs = temp.newFolder("removal-rootfs")
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("m1")))
        memory.store.syncToRuntime(rootfs)

        memory.store.remove("acme")
        val configFile = memory.store.syncToRuntime(rootfs)

        val providers = Json.parseToJsonElement(configFile.readText()).jsonObject["provider"]?.jsonObject.orEmpty()
        assertFalse("acme" in providers)
        assertTrue(memory.syncedIds.isEmpty())
    }

    @Test
    fun `malformed existing config is preserved and sync fails`() {
        val rootfs = temp.newFolder("malformed-rootfs")
        val configFile =
            File(rootfs, "root/.config/opencode/opencode.json").apply {
                parentFile.mkdirs()
                writeText("not-json")
            }
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("m1")))

        assertThrows(IllegalStateException::class.java) {
            memory.store.syncToRuntime(rootfs)
        }

        assertEquals("not-json", configFile.readText())
    }

    private fun memoryStore(): MemoryProviderStore {
        val definitions = mutableListOf<CustomProviderDefinition>()
        val syncedIds = mutableSetOf<String>()
        val store =
            CustomProviderStore(
                load = { definitions.toList() },
                save = {
                    definitions.clear()
                    definitions.addAll(it)
                },
                loadSyncedIds = { syncedIds.toSet() },
                saveSyncedIds = {
                    syncedIds.clear()
                    syncedIds.addAll(it)
                },
            )
        return MemoryProviderStore(store, syncedIds)
    }

    private data class MemoryProviderStore(
        val store: CustomProviderStore,
        val syncedIds: MutableSet<String>,
    )
}
