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
import java.io.Closeable
import java.text.ParseException
import java.util.Locale

class SpeechResolversVM(
    application: Application,
    val resolvers: Map<SpeechExecutor<*, *>, ResolverMetadata>
) : AndroidViewModel(application) {
    companion object {
        val RES_LIST_KEY =
            object : CreationExtras.Key<Map<SpeechExecutor<*, *>, ResolverMetadata>> {}

        val Factory = viewModelFactory {
            initializer {
                SpeechResolversVM(
                    this[APPLICATION_KEY]!!,
                    this[RES_LIST_KEY] ?: emptyMap()
                )
            }
        }
    }

    private val _speechResult = MutableLiveData<String>()
    val speechText = _speechResult
    private val _resolutionName = MutableLiveData<String>()
    val resolutionName = _resolutionName

    data class ResolverMetadata(
        @field:StringRes
        val nameId: Int,
        @field:DrawableRes
        val iconId: Int,
    )

    enum class State { IDLE, ACTIVE }
    data class ResolverStatus(
        val state: State = State.IDLE,
        val result: Result<String>? = null
    )

    private val _resolverStatuses = MutableLiveData<Map<SpeechExecutor<*, *>, ResolverStatus>>(
        buildResetStatuses()
    )
    val resolverStatuses: LiveData<Map<SpeechExecutor<*, *>, ResolverStatus>> = _resolverStatuses

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            _speechResult.postValue(s)

            val lr = with(getApplication<Application>()) {
                locale?.let {
                    this.getLocalizedResources(locale)
                } ?: this.resources
            }

            viewModelScope.launch {
                _resolverStatuses.postValue(buildResetStatuses())

                val statuses = buildResetStatuses().mapValues {
                    it.value.copy(state = State.IDLE)
                }.toMutableMap()

                resolvers.forEach { (r, data) ->
                    val status = statuses[r] ?: ResolverStatus()
                    statuses[r] = status.copy(state = State.ACTIVE)
                    _resolverStatuses.postValue(statuses.toMap())

                    val resolution = r.resolveToExecute(s, locale ?: Locale.getDefault())

                    if (resolution == null) {
                        statuses[r] = (statuses[r] ?: ResolverStatus()).copy(
                            state = State.IDLE,
                            result = Result.failure(ParseException("", 0))
                        )
                        _resolverStatuses.postValue(statuses.toMap())
                        return@forEach
                    }

                    val (_, function, desc) = resolution

                    statuses[r] = status.copy(
                        state = State.IDLE,
                        result = Result.success(desc.name)
                    )
                    _resolverStatuses.postValue(statuses.toMap())

                    try {
                        playSfx(SFXManager.SFX.ACTION_CONFIRM)
                        speak(lr.getString(R.string.commands_response_fmt_accepted) + ". ", locale)
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

    private fun buildResetStatuses(): Map<SpeechExecutor<*, *>, ResolverStatus> =
        resolvers.mapValues { ResolverStatus() }.toMap()

    override fun onCleared() {
        super.onCleared()
        resolvers.keys.forEach {
            if (it is Closeable)
                it.close()
        }
    }
}
