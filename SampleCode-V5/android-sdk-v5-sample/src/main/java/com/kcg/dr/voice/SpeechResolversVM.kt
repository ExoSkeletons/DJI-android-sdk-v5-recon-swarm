package com.kcg.dr.voice

import android.app.Application
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.text.ParseException
import java.util.Locale

class SpeechResolversVM(
    application: Application,
    private val resolvers: Map<SpeechExecutor<*, *>, ResolverItem>
) : AndroidViewModel(application) {
    companion object {
        val RES_LIST_KEY = object : CreationExtras.Key<Map<SpeechExecutor<*, *>, ResolverItem>> {}

        val Factory = viewModelFactory {
            initializer {
                SpeechResolversVM(
                    this[APPLICATION_KEY]!!,
                    (this[RES_LIST_KEY] ?: emptyMap())
                )
            }
        }
    }

    private val _speechResult = MutableLiveData<String>()
    val speechText = _speechResult
    private val _resolutionName = MutableLiveData<String>()
    val resolutionName = _resolutionName
    private val _resolutionResponse = MutableLiveData<String>()
    val resolutionResponse = _resolutionResponse

    data class ResolverItem(
        @field:StringRes val nameId: Int,
        @field:DrawableRes val iconId: Int,
    )

    enum class State { IDLE, ACTIVE }

    data class ResolverStatus(
        val state: State = State.IDLE,
        val result: Result<String>? = null
    )

    data class ResolverViewState(
        val item: ResolverItem,
        val status: ResolverStatus,
    )

    private val statuses = mutableMapOf<SpeechExecutor<*, *>, ResolverStatus>().apply {
        resolvers.keys.forEach { put(it, ResolverStatus()) }
    }

    private val _uiStates = MutableLiveData<List<ResolverViewState>>(buildUiStates())
    val uiStates: LiveData<List<ResolverViewState>> = _uiStates

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            _speechResult.postValue(s)
            resetStatuses()
            emitUiStates()

            val context = getApplication<Application>().applicationContext
            val lr = with(context) {
                locale?.let {
                    this.getLocalizedResources(locale)
                } ?: this.resources
            }

            viewModelScope.launch {
                resolvers.forEach { (r, d) ->
                    statuses[r] = ResolverStatus(state = State.ACTIVE)
                    emitUiStates()

                    val resolution = withContext(Dispatchers.Default) {
                        r.resolveToExecute(s, locale ?: Locale.getDefault())
                    }
                    if (resolution == null) {
                        statuses[r] = ResolverStatus(
                            State.IDLE,
                            Result.failure(ParseException("", 0))
                        )
                        emitUiStates()
                        return@forEach
                    }

                    val (_, function, desc) = resolution

                    statuses[r] = ResolverStatus(State.IDLE, Result.success(desc.name))
                    emitUiStates()

                    playSfx(SFXManager.SFX.ACTION_CONFIRM)
                    context.speak(R.string.commands_response_fmt_accepted, locale = locale)
                    speak(desc.response, locale)
                    _resolutionName.postValue(desc.name)
                    _resolutionResponse.postValue(desc.response)

                    launch(Dispatchers.IO) {
                        try {
                            function()
                        } catch (e: Exception) {
                            playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                            _resolutionName.postValue(e.message ?: e.toString())
                        }
                    }
                    return@launch
                }

                playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                speak(lr.getString(R.string.error_speech_unrecognised), locale = locale)
                _resolutionName.postValue(lr.getString(R.string.error_speech_unrecognised))
            }
        }
    }

    private fun resetStatuses() = resolvers.keys.forEach { statuses[it] = ResolverStatus() }

    private fun emitUiStates() {
        _uiStates.value = buildUiStates()
    }

    private fun buildUiStates(): List<ResolverViewState> = resolvers.map { (r, d) ->
        ResolverViewState(d, statuses[r] ?: ResolverStatus())
    }

    override fun onCleared() {
        super.onCleared()
        resolvers.keys.forEach {
            if (it is Closeable)
                it.close()
        }
    }
}
