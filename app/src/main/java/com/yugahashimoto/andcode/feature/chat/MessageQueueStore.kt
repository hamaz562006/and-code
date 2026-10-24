package com.yugahashimoto.andcode.feature.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the follow-up / offline message queue across process death (feature 3).
 * Attachments are not persisted — only text prompts survive restart.
 */
class MessageQueueStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadTexts(): List<String> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i).trim()
                    if (t.isNotEmpty()) add(t)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveTexts(texts: List<String>) {
        val arr = JSONArray()
        texts.forEach { arr.put(it) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    companion object {
        private const val PREFS = "andcode_message_queue"
        private const val KEY_QUEUE = "queue_texts"

        @Volatile
        private var instance: MessageQueueStore? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = MessageQueueStore(context.applicationContext)
                }
            }
        }

        fun get(): MessageQueueStore? = instance
    }
}
