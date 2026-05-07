package com.kcg.dr.voice

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.kcg.dr.SFXManager
import dji.sampleV5.aircraft.R
import java.util.Locale

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    val silent = MutableLiveData(false)
    val speechResult = MutableLiveData<String>()
    val commandResult = MutableLiveData<String>()
    
    private val commandResolver = CommandResolver()
    private var tts: TextToSpeech? = null
    private val locale = Locale("iw", "IL")

    fun initTTS(context: Context, onInit: (Int) -> Unit) {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                onInit(status)
            }
        }
    }

    fun speak(text: String) {
        if (text.isNotBlank() && silent.value != true) {
            tts?.let {
                if (it.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    it.language = locale
                    it.setSpeechRate(1.3f)
                    SFXManager.playSfx(SFXManager.SFX.NOTIFY_INFO)
                    it.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }

    fun setCommands(commands: List<CommandResolver.Command>) {
        commandResolver.commands.clear()
        commandResolver.commands.addAll(commands)
    }

    fun processSpeech(spokenText: String) {
        speechResult.postValue(spokenText)

        val resources = getApplication<Application>().resources

        val resolve = commandResolver.resolve(spokenText, resources)
        if (resolve == null) {
            commandResult.postValue(resources.getString(R.string.error_speech_unrecognised))
            return
        }

        val (com, match) = resolve
        try {
            com.func(match)
            SFXManager.playSfx(SFXManager.SFX.ACTION_CONFIRM)
            com.response(resources)?.let {
                speak(resources.getString(R.string.commands_response_fmt_accepted) + ". " + it)
            }
            commandResult.postValue(com.name(resources))
        } catch (e: Exception) {
            SFXManager.playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
            commandResult.postValue(e.message ?: e.toString())
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
