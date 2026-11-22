package com.blurr.voice.publicapis

import kotlinx.serialization.Serializable

@Serializable
data class PublicApiEntry(
    val api: String,
    val description: String,
    val auth: String,
    val https: Boolean,
    val cors: String,
    val link: String,
    val category: String
)
