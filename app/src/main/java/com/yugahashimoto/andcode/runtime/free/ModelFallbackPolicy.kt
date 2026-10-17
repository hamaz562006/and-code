package com.yugahashimoto.andcode.runtime.free

/**
 * Ordered backup models when the primary request fails (quota / rate limit / unavailable).
 * Mirrors AndroidHarness FallbackPolicy: up to [MAX_TARGETS] targets, optional same-provider-only.
 */
data class FallbackTarget(
    val providerId: String,
    val modelId: String,
)

data class ModelFallbackPolicy(
    val enabled: Boolean = true,
    val targets: List<FallbackTarget> = emptyList(),
    /** Extra attempts on the same model before switching (0–5). */
    val retries: Int = 1,
    val sameProviderOnly: Boolean = false,
) {
    fun valid(): Boolean =
        retries in 0..5 &&
            targets.size <= MAX_TARGETS &&
            targets.distinctBy { "${it.providerId}/${it.modelId}" }.size == targets.size &&
            targets.all { it.providerId.isNotBlank() && it.modelId.isNotBlank() }

    companion object {
        const val MAX_TARGETS = 4
    }
}

data class FallbackAttemptResult(
    val modelId: String,
    val providerId: String,
    val text: String,
    val switched: Boolean,
    val attempted: List<String>,
)
