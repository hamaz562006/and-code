package com.yugahashimoto.andcode.runtime.free

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the global model-fallback chain (all agents).
 * Default: enabled, first free models from [FreeModelsCatalog] as backups.
 */
class ModelFallbackStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ModelFallbackPolicy {
        if (!prefs.contains(KEY_ENABLED)) {
            return defaultPolicy()
        }
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val retries = prefs.getInt(KEY_RETRIES, 1).coerceIn(0, 5)
        val sameProviderOnly = prefs.getBoolean(KEY_SAME_PROVIDER, false)
        val targets =
            runCatching {
                val raw = prefs.getString(KEY_TARGETS, null) ?: return@runCatching emptyList()
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            FallbackTarget(
                                providerId = o.getString("providerId"),
                                modelId = o.getString("modelId"),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        val policy =
            ModelFallbackPolicy(
                enabled = enabled,
                targets = targets.take(ModelFallbackPolicy.MAX_TARGETS),
                retries = retries,
                sameProviderOnly = sameProviderOnly,
            )
        return if (policy.valid()) policy else defaultPolicy()
    }

    fun save(policy: ModelFallbackPolicy) {
        require(policy.valid()) { "Invalid fallback policy" }
        val arr = JSONArray()
        policy.targets.forEach { t ->
            arr.put(
                JSONObject()
                    .put("providerId", t.providerId)
                    .put("modelId", t.modelId),
            )
        }
        prefs.edit()
            .putBoolean(KEY_ENABLED, policy.enabled)
            .putInt(KEY_RETRIES, policy.retries)
            .putBoolean(KEY_SAME_PROVIDER, policy.sameProviderOnly)
            .putString(KEY_TARGETS, arr.toString())
            .apply()
    }

    /**
     * Chain for a request: primary first, then configured targets (skipping primary),
     * then remaining free models if the chain is empty.
     */
    fun chainFor(
        primaryProviderId: String?,
        primaryModelId: String?,
    ): List<FallbackTarget> {
        val policy = load()
        if (!policy.enabled) {
            return listOfNotNull(
                primaryModelId?.takeIf { it.isNotBlank() }?.let {
                    FallbackTarget(primaryProviderId ?: FreeModelsCatalog.PROVIDER_ID, it)
                },
            )
        }
        val primary =
            FallbackTarget(
                providerId = primaryProviderId?.takeIf { it.isNotBlank() } ?: FreeModelsCatalog.PROVIDER_ID,
                modelId = primaryModelId?.takeIf { it.isNotBlank() } ?: FreeModelsCatalog.DEFAULT_MODEL,
            )
        val configured =
            policy.targets.filter {
                !(it.providerId == primary.providerId && it.modelId == primary.modelId) &&
                    (!policy.sameProviderOnly || it.providerId == primary.providerId)
            }
        val autoFree =
            if (configured.isEmpty()) {
                FreeModelsCatalog.modelIds()
                    .filter { it != primary.modelId }
                    .take(ModelFallbackPolicy.MAX_TARGETS)
                    .map { FallbackTarget(FreeModelsCatalog.PROVIDER_ID, it) }
            } else {
                emptyList()
            }
        return (listOf(primary) + configured + autoFree)
            .distinctBy { "${it.providerId}/${it.modelId}" }
            .take(1 + ModelFallbackPolicy.MAX_TARGETS)
    }

    companion object {
        private const val PREFS = "andcode_model_fallback"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RETRIES = "retries"
        private const val KEY_SAME_PROVIDER = "same_provider_only"
        private const val KEY_TARGETS = "targets"

        @Volatile
        private var instance: ModelFallbackStore? = null

        fun init(context: android.content.Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = ModelFallbackStore(context.applicationContext)
                    }
                }
            }
        }

        fun get(): ModelFallbackStore? = instance

        fun defaultPolicy(): ModelFallbackPolicy {
            val targets =
                FreeModelsCatalog.modelIds()
                    .filter { it != FreeModelsCatalog.DEFAULT_MODEL }
                    .take(ModelFallbackPolicy.MAX_TARGETS)
                    .map { FallbackTarget(FreeModelsCatalog.PROVIDER_ID, it) }
            return ModelFallbackPolicy(
                enabled = true,
                targets = targets,
                retries = 1,
                sameProviderOnly = false,
            )
        }
    }
}
