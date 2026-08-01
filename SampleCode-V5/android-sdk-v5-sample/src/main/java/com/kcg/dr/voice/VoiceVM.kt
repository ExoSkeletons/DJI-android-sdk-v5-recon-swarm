package com.kcg.dr.voice

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import com.kcg.dr.utils.SFXManager
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "VoiceViewModel"

class VoiceVM(application: Application) : AndroidViewModel(application) {
    val silent = MutableLiveData(false)
    val speechResult = MutableLiveData<String>()
    val commandResult = MutableLiveData<String>()

    private val commandResolver = RegexCommandResolver(application.resources)
    private var tts: TextToSpeech = TextToSpeech(getApplication()) { status ->
        if (status != TextToSpeech.SUCCESS) {
            silent.postValue(true)
            Toast.makeText(getApplication(), "TTS init failed", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "TTS init failed")
            return@TextToSpeech
        }
        Log.i(TAG, "TTS init success")
    }

    fun speak(
        text: String,
        locale: Locale = Locale("iw", "IL"),
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        onLangUnavailable: ((TextToSpeech, Locale) -> Unit)? = null
    ) {
        if (text.isNotBlank() && silent.value != true) tts.apply {
            if (isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                onLangUnavailable?.invoke(this, locale)
                return
            }
            language = locale
            setSpeechRate(1.3f)
            SFXManager.playSfx(SFXManager.SFX.NOTIFY_INFO)
            speak(text, queueMode, null, null)
        }
    }

    // user of vm calls this to set the commands
    fun setCommands(commands: Collection<RCommandResolver.Command<MatchResult>> = emptyList()) {
        commandResolver.commands.clear()
        commandResolver.commands.addAll(commands)
    }

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            speechResult.postValue(s)

            val lr = with(getApplication<Application>()) {
                locale?.let {
                    this.getLocalizedResources(locale)
                } ?: this.resources
            }

            viewModelScope.launch {
                commandResolver.resources = lr
                val resolution = commandResolver.resolveToExecute(s)
                if (resolution == null) {
                    commandResult.postValue(lr.getString(R.string.error_speech_unrecognised))
                    return@launch
                }

                val (action, function) = resolution
                try {
                    SFXManager.playSfx(SFXManager.SFX.ACTION_CONFIRM)
                    speak(lr.getString(R.string.commands_response_fmt_accepted) + ". ")
                    commandResult.postValue(commandResolver.nameOf(action))
                    viewModelScope.launch(Dispatchers.IO) {
                        function()
                    }
                    speak(commandResolver.responseTo(action))
                } catch (e: Exception) {
                    SFXManager.playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                    commandResult.postValue(e.message ?: e.toString())
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts.apply {
            stop()
            shutdown()
        }
    }
}
