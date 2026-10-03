package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads the latest published version of the Pi npm package. */
object PiReleaseClient {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestVersion(): String =
        withContext(Dispatchers.IO) {
            val url = "https://registry.npmjs.org/${PiInstaller.NPM_PACKAGE}/latest"
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "npm registry HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                val version =
                    json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content?.trim().orEmpty()
                require(version.isNotEmpty()) { "npm registry returned no version" }
                version
            }
        }
}
