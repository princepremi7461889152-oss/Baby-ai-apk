package com.blurr.voice.api

data class ConnectionTestResult(
    val success: Boolean,
    val provider: String,
    val model: String,
    val latencyMs: Long = 0L,
    val message: String
)
