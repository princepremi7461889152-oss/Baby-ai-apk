package com.blurr.voice.utilities

import java.util.concurrent.atomic.AtomicInteger

/**
 * Legacy helper retained safely for optional REST embedding utilities.
 * Primary Gemini inference uses Firebase AI Logic (App Check attested, no API key needed).
 */
object ApiKeyManager {

    private val apiKeys: List<String> = emptyList()
    private val currentIndex = AtomicInteger(0)

    fun hasKeys(): Boolean = apiKeys.isNotEmpty()

    fun getNextKey(): String {
        if (apiKeys.isEmpty()) {
            return ""
        }
        val index = currentIndex.getAndIncrement() % apiKeys.size
        return apiKeys[index]
    }
}