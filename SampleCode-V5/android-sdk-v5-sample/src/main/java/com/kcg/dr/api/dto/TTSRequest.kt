@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto

import android.speech.tts.TextToSpeech
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class TTSRequest(
    val text: String,
    val lang: String = "en",
    val country: String? = null,
    val rate: Double = 1.0,
)