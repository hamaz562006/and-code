package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog

object PiModels {
    const val PROVIDER_ID = "pi"
    const val DEFAULT_MODEL = "default"

    fun catalog(models: List<String> = emptyList()): ProviderCatalog {
        val modelList = if (models.isNotEmpty()) models else listOf(DEFAULT_MODEL)
        return ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = PROVIDER_ID,
                        name = "Pi",
                        models =
                            modelList.associateWith { id ->
                                OpenCodeModel(
                                    id = id,
                                    providerId = PROVIDER_ID,
                                    name = if (id == DEFAULT_MODEL) "Default (Configured in Pi)" else id,
                                )
                            },
                    ),
                ),
            default = mapOf(PROVIDER_ID to (modelList.firstOrNull() ?: DEFAULT_MODEL)),
            connected = listOf(PROVIDER_ID),
        )
    }
}
