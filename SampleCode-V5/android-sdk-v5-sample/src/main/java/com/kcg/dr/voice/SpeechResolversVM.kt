package com.kcg.dr.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
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
import java.text.ParseException
import java.util.Locale

class SpeechResolversVM(
    application: Application,
    private val resolvers: List<SpeechExecutor<*, *>>
) : AndroidViewModel(application) {
    companion object {
        val RES_LIST_KEY = object : CreationExtras.Key<List<SpeechExecutor<*, *>>> {}

        val Factory = viewModelFactory {
            initializer {
                SpeechResolversVM(
                    this[APPLICATION_KEY]!!,
                    this[RES_LIST_KEY] ?: emptyList()
                )
            }
        }
    }

    private val _speechResult = MutableLiveData<String>()
    val speechText = _speechResult
    private val _resolutionName = MutableLiveData<String>()
    val resolutionName = _resolutionName

    enum class State { IDLE, ACTIVE }
    data class ResolverStatus(
        val name: String,
        val state: State = State.IDLE,
        val result: Result<String>? = null
    )

    private val _resolverStatuses = MutableLiveData<List<ResolverStatus>>(
        resolvers.map { ResolverStatus(it::class.java.simpleName) }
    )
    val resolverStatuses: LiveData<List<ResolverStatus>> = _resolverStatuses

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            _speechResult.postValue(s)

            val lr = with(getApplication<Application>()) {
                locale?.let {
                    this.getLocalizedResources(locale)
                } ?: this.resources
            }

            viewModelScope.launch {
                val statuses = resolvers.map {
                    ResolverStatus(it::class.java.simpleName, State.IDLE, null)
                }.toMutableList()
                _resolverStatuses.postValue(statuses.toList())

                resolvers.forEachIndexed { i, it ->
                    statuses[i] = statuses[i].copy(state = State.ACTIVE)
                    _resolverStatuses.postValue(statuses.toList())

                    val resolution = it.resolveToExecute(s, locale ?: Locale.getDefault())

                    if (resolution == null) {
                        statuses[i] =
                            statuses[i].copy(result = Result.failure(ParseException("", 0)))
                        _resolverStatuses.postValue(statuses.toList())
                        return@forEachIndexed
                    }

                    val (_, function, desc) = resolution

                    statuses[i] = statuses[i].copy(result = Result.success(desc.name))
                    _resolverStatuses.postValue(statuses.toList())

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
                        return@launch
                    } catch (e: Exception) {
                        playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                        _resolutionName.postValue(e.message ?: e.toString())
                    }
                }

                playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                _resolutionName.postValue(lr.getString(R.string.error_speech_unrecognised))
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
