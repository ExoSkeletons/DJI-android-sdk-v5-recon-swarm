package com.kcg.dr.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import java.util.Locale

object TTSManager {
    const val TAG = "TTS-Manager"

    private var tts: TextToSpeech? = null
    val silent = MutableLiveData(false)

    fun init(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                silent.postValue(true)
                Toast.makeText(context, "TTS init failed", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "TTS init failed")
                return@TextToSpeech
            }
            Log.i(TAG, "TTS init success")
        }
    }

    fun Context.speak(
        @StringRes
        textId: Int,
        vararg formatArgs: Any,
        locale: Locale? = Locale("iw", "IL"),
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null
    ) = speak(
        (locale?.let {
            this.getLocalizedResources(locale)
        } ?: this.resources)
            .getString(textId, formatArgs),
        locale,
        queueMode,
        onLangUnavailable
    )

    fun speak(
        text: String,
        locale: Locale? = Locale("iw", "IL"),
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null
    ) {
        if (text.isNotBlank() && silent.value != true) tts?.apply {
            if (locale != null && isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                onLangUnavailable?.invoke(this, locale)
                return
            }
            language = locale ?: Locale.getDefault()
            setSpeechRate(1.3f)
            SFXManager.playSfx(SFXManager.SFX.NOTIFY_INFO)
            speak(text, queueMode, null, null)
        }
    }

    fun release() {
        tts?.apply {
            stop()
            shutdown()
        }
    }
}