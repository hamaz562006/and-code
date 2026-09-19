package com.yugahashimoto.andcode.data.settings

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppPreferencesRepositoryTest {
    @Test
    fun `keeps a saved model when that provider is not in the connected snapshot`() {
        val result =
            reconcileModelSelection(
                currentProviderId = "nvidia",
                currentModelId = "kimi-k2.5",
                catalog =
                    catalog(
                        connected = listOf("opencode"),
                        providers =
                            listOf(
                                provider("opencode", "big-pickle"),
                                provider("nvidia", "kimi-k2.5"),
                            ),
                        default = mapOf("opencode" to "big-pickle"),
                    ),
                recentModelKeys = listOf("nvidia/kimi-k2.5"),
            )

        assertNull(result)
    }

    @Test
    fun `keeps a saved model that is still connected`() {
        val result =
            reconcileModelSelection(
                currentProviderId = "deepseek",
                currentModelId = "deepseek-chat",
                catalog =
                    catalog(
                        connected = listOf("opencode", "deepseek"),
                        providers =
                            listOf(
                                provider("opencode", "big-pickle"),
                                provider("deepseek", "deepseek-chat", "deepseek-reasoner"),
                            ),
                    ),
            )

        assertEquals(ReconciledModelSelection("deepseek", "deepseek-chat"), result)
    }

    @Test
    fun `picks another model from the same provider when the saved one is gone`() {
        val result =
            reconcileModelSelection(
                currentProviderId = "deepseek",
                currentModelId = "retired",
                catalog =
                    catalog(
                        connected = listOf("deepseek"),
                        providers = listOf(provider("deepseek", "deepseek-chat")),
                        default = mapOf("deepseek" to "deepseek-chat"),
                    ),
            )

        assertEquals(ReconciledModelSelection("deepseek", "deepseek-chat"), result)
    }

    @Test
    fun `fills a default when nothing is saved yet`() {
        val result =
            reconcileModelSelection(
                currentProviderId = null,
                currentModelId = null,
                catalog =
                    catalog(
                        connected = listOf("opencode"),
                        providers = listOf(provider("opencode", "big-pickle")),
                        default = mapOf("opencode" to "big-pickle"),
                    ),
            )

        assertEquals(ReconciledModelSelection("opencode", "big-pickle"), result)
    }

    @Test
    fun `does not invent a selection against an empty catalogue`() {
        assertNull(
            reconcileModelSelection(
                currentProviderId = "nvidia",
                currentModelId = "kimi-k2.5",
                catalog = ProviderCatalog(),
            ),
        )
    }

    private fun catalog(
        connected: List<String>,
        providers: List<OpenCodeProvider>,
        default: Map<String, String> = emptyMap(),
    ) = ProviderCatalog(all = providers, default = default, connected = connected)

    private fun provider(
        id: String,
        vararg modelIds: String,
    ) = OpenCodeProvider(
        id = id,
        name = id,
        models = modelIds.associateWith { OpenCodeModel(id = it, providerId = id, name = it) },
    )
}
