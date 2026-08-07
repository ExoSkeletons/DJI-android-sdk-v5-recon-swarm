package com.kcg.dr.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import com.kcg.dr.utils.SFXManager
import com.kcg.dr.utils.SFXManager.playSfx
import com.kcg.dr.utils.TTSManager.speak
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Locale

class VoiceVM(
    application: Application,
    private val resolvers: List<SpeechExecutor<*, *>>
) : AndroidViewModel(application) {
    companion object {
        val RES_LIST_KEY = object : CreationExtras.Key<List<SpeechExecutor<*, *>>> {}

        val Factory = viewModelFactory {
            initializer {
                VoiceVM(
                    this[APPLICATION_KEY]!!,
                    this[RES_LIST_KEY] ?: emptyList()
                )
            }
        }
    }

    private val _speechResult = MutableLiveData<String>()
    val speechResult = _speechResult
    private val _resolutionName = MutableLiveData<String>()
    val resolutionName = _resolutionName
    private val _resolutionResponse = MutableLiveData<String>()
    val resolutionResponse = _resolutionResponse

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            _speechResult.postValue(s)

            val lr = with(getApplication<Application>()) {
                locale?.let {
                    this.getLocalizedResources(locale)
                } ?: this.resources
            }

            viewModelScope.launch {
                resolvers.forEach {
                    val resolution = it.resolveToExecute(s, locale ?: Locale.getDefault())
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
        for (resolver in resolvers)
            if (resolver is Closeable)
                resolver.close()
    }
}
