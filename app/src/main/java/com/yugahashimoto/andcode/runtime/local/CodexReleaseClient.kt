package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A concrete `@openai/codex` release for one Android ABI, resolved from the npm registry. */
data class CodexRelease(
    val version: String,
    val tarballUrl: String,
    /** `sha512-<base64>`, the Subresource Integrity string npm itself records for this tarball. */
    val integrity: String,
)

/**
 * Resolves the newest `@openai/codex` release for one Android ABI from the public npm registry.
 *
 * Codex has no Alpine package and no GitHub release, unlike Claude Code and Antigravity: it ships
 * as an npm package whose native binary lives in a per-platform *optional dependency*, published
 * under the same package name at a synthetic version (`<version>-linux-arm64`) - verified by hand
 * against the real registry (`npm view @openai/codex versions`, `npm view
 * @openai/codex@<version>-linux-x64 dist`). That per-platform metadata is what carries the tarball's
 * own `integrity` (SRI SHA-512), which is what this app pins the download against - the same
 * "trust the official channel's own recorded digest over HTTPS" pattern
 * [AntigravityReleaseClient] uses for GitHub's SHA-256, just from a different registry and a
 * stronger hash.
 */
class CodexReleaseClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val registry: HttpUrl = OFFICIAL_REGISTRY.toHttpUrl(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    init {
        require(registry.isHttps || registry.host in LOOPBACK_HOSTS) { "Codex registry endpoint must use HTTPS" }
    }

    suspend fun latest(abi: String): CodexRelease =
        withContext(Dispatchers.IO) {
            val platform =
                requireNotNull(PLATFORM_BY_ABI[abi]) { "Unsupported Android ABI for Codex: $abi" }
            val baseVersion = fetchPackage("$PACKAGE_NAME/latest").version
            val platformPackage = fetchPackage("$PACKAGE_NAME/$baseVersion-$platform")
            val dist =
                requireNotNull(platformPackage.dist) {
                    "Codex release $baseVersion has no platform package for $platform"
                }
            require(SRI_SHA512.matches(dist.integrity)) { "Codex release asset is missing a valid SHA-512 integrity string" }
            val tarballUrl = dist.tarball.toHttpUrl()
            require(tarballUrl.isHttps) { "Codex release asset URL must use HTTPS" }
            // A sanity check on the release metadata itself, not on the eventual download: this is
            // the *uncompressed* tarball size npm records, not the compressed .tgz's byte length, so
            // it cannot be compared against the downloaded file - the download is verified against
            // `integrity` alone, hence not carried into CodexRelease at all.
            require(dist.unpackedSize > 0L) { "Codex release asset size must be positive" }

            CodexRelease(
                version = baseVersion,
                tarballUrl = tarballUrl.toString(),
                integrity = dist.integrity,
            )
        }

    private fun fetchPackage(path: String): NpmPackageDto {
        val url = registry.newBuilder().addPathSegments(path.trimStart('/')).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Codex registry lookup failed for $path with HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "Codex registry response for $path had no body" }
            json.decodeFromString<NpmPackageDto>(body.string())
        }
    }

    @Serializable
    private data class NpmPackageDto(
        @SerialName("version") val version: String,
        @SerialName("dist") val dist: NpmDistDto? = null,
    )

    @Serializable
    private data class NpmDistDto(
        @SerialName("tarball") val tarball: String,
        @SerialName("integrity") val integrity: String,
        /**
         * The tarball's own compressed size is not published; `unpackedSize` is, and is only used
         * here as a non-zero sanity bound before the integrity check runs - the download is verified
         * against [integrity], not this figure.
         */
        @SerialName("unpackedSize") val unpackedSize: Long = 0L,
    )

    companion object {
        const val OFFICIAL_REGISTRY = "https://registry.npmjs.org/"
        private const val PACKAGE_NAME = "@openai/codex"
        private const val USER_AGENT = "AndCode"
        private val SRI_SHA512 = Regex("^sha512-[A-Za-z0-9+/]+=*$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        /** Codex's own npm platform-package suffixes (`bin/codex.js`'s `PLATFORM_PACKAGE_BY_TARGET`). */
        private val PLATFORM_BY_ABI =
            mapOf(
                "arm64-v8a" to "linux-arm64",
                "x86_64" to "linux-x64",
            )
    }
}
