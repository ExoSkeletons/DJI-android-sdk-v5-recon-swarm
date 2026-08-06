package com.kcg.dr.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kcg.dr.utils.SFXManager
import com.kcg.dr.utils.SFXManager.playSfx
import com.kcg.dr.utils.TTSManager.speak
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceVM(application: Application) : AndroidViewModel(application) {
    private val _speechResult = MutableLiveData<String>()
    val speechResult = _speechResult
    private val _resolutionName = MutableLiveData<String>()
    val resolutionName = _resolutionName
    private val _resolutionResponse = MutableLiveData<String>()
    val resolutionResponse = _resolutionResponse

    private val commandResolver = RegexCommandResolver(application)
    // private val actionResolver = LlamaActionSequenceResolver(application)
    private val resolvers = listOf(
        commandResolver,
        // actionResolver
    )
    // user of vm calls this to set the commands
    fun setCommands(commands: Collection<CommandResolver.Command<MatchResult>> = emptyList()) {
        commandResolver.commands.clear()
        commandResolver.commands.addAll(commands)
    }

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            _speechResult.postValue(s)

            val lr = with(getApplication<Application>()) {
                locale?.let {
                    this.getLocalizedResources(locale)
                } ?: this.resources
            }

            viewModelScope.launch {
                commandResolver.locale = locale

                resolvers.forEach {
                    val resolution = it.resolveToExecute(s)
                    if (resolution == null) {
                        _resolutionName.postValue(lr.getString(R.string.error_speech_unrecognised))
                        return@launch
                    }

                    val (_, function, desc) = resolution
                    try {
                        playSfx(SFXManager.SFX.ACTION_CONFIRM)
                        speak(
                            lr.getString(R.string.commands_response_fmt_accepted) + ". ",
                            locale
                        )
                        _resolutionName.postValue(desc.name)
                        viewModelScope.launch(Dispatchers.IO) {
                            function()
                        }
                        speak(desc.response, locale)
                    } catch (e: Exception) {
                        playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                        _resolutionName.postValue(e.message ?: e.toString())
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // actionResolver.destroy()
    }
}
