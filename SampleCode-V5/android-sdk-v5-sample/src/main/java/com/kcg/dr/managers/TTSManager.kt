package com.kcg.dr.managers

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.utils.getLocalizedResources
import dji.sampleV5.aircraft.R
import java.util.Locale

object TTSManager {
    const val TAG = "TTS-Manager"

    private val preferredTTSEngine = "com.google.android.tts"

    private var tts: TextToSpeech? = null
    private var onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null
    val silent = MutableLiveData(false)

    fun init(context: Context, onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null) {
        if (tts != null) {
            Log.i(TAG, "TTS already initialized")
            return
        }
        Log.d(TAG, "Initializing TTS...")
        tts = TextToSpeech(context, { status ->
            Log.i(TAG, "TTS init status: $status")
            if (status != TextToSpeech.SUCCESS) {
                silent.postValue(true)
                Toast.makeText(context, "TTS init failed", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "TTS init failed")
                return@TextToSpeech
            }
            Log.d(TAG, "TTS engine init success")
            if (!context.checkAndPromptPreferredTTSEngine())
                Toast.makeText(
                    context,
                    "This app works best with the $preferredTTSEngine TTS.",
                    Toast.LENGTH_LONG
                ).show()
            this.onLangUnavailable = onLangUnavailable
            Log.d(TAG, "TTS init complete")
        }, preferredTTSEngine)
    }

    private fun Context.checkAndPromptPreferredTTSEngine(): Boolean {
        val currentEngine = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH
        )
        Log.i(TAG, "Current TTS engine: $currentEngine")

        if (currentEngine != preferredTTSEngine) {
            Log.d(TAG, "Prompting switch to TTS engine $preferredTTSEngine...")
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.tts_switch_engine_prompt))
                .setMessage(getString(R.string.tts_switch_engine_prompt_details))
                .setPositiveButton("Open Settings") { dialog, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(
                            this,
                            "Unable to open settings",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .show()
            return false
        }
        return true
    }

    fun promptInstallTTSLanguage(context: Context) {
        Log.d(TAG, "Prompting install of TTS languages...")
        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        try {
            context.startActivity(installIntent)
        } catch (_: Exception) {
        }
    }

    fun speak(
        text: String,
        locale: Locale? = Locale.getDefault(),
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null
    ) {
        if (text.isNotBlank() && silent.value != true) tts?.apply {
            if (locale != null && isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                Log.w(TAG, "Speak called but Language not available: $locale")
                this@TTSManager.onLangUnavailable?.invoke(this, locale)
                onLangUnavailable?.invoke(this, locale)
                return
            }
            language = locale ?: Locale.getDefault()
            setSpeechRate(1.3f)
            SFXManager.playSfx(SFXManager.SFX.NOTIFY_INFO)
            speak(text, queueMode, null, null)
        }
    }

    fun Context.speak(
        @StringRes
        textId: Int,
        vararg formatArgs: Any,
        locale: Locale?,
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

    fun Fragment.speak(
        @StringRes
        textId: Int,
        vararg formatArgs: Any,
        locale: Locale?,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null
    ) = requireContext().speak(
        textId, formatArgs = formatArgs, locale, queueMode, onLangUnavailable
    )

    fun release() {
        tts?.apply {
            stop()
            shutdown()
        }
        tts = null
    }
}