package com.kcg.dr.voice

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
import com.kcg.dr.utils.ServiceUtils
import com.kcg.dr.utils.TTSManager.speak
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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

    private val _isListening = MutableLiveData(false)
    val isListening: LiveData<Boolean> = _isListening
    private val _partialSpeech = MutableLiveData("")
    val partialSpeech: LiveData<String> = _partialSpeech
    private val _speech = MutableLiveData("")
    val speech: LiveData<String> = _speech

    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)

    init {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechResolversVM", "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d("SpeechResolversVM", "onBeginningOfSpeech")
                _partialSpeech.value = ""
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("SpeechResolversVM", "onEndOfSpeech")
                _isListening.value = false
                playSfx(SFXManager.SFX.ACTION_CONFIRM)
            }

            override fun onError(error: Int) {
                _isListening.value = false
                val errorText = with(getApplication<Application>().applicationContext) {
                    when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NETWORK -> getString(R.string.app_network_error)
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                        SpeechRecognizer.ERROR_NO_MATCH -> getString(R.string.error_speech_unrecognised)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
                        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
                        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
                        else -> null
                    }?: ""
                }
                _speech.value = errorText
                Log.e("SpeechResolversVM", "Speech recognition error: $errorText")
                playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("SpeechResolversVM", "onResults: $matches")
                matches?.firstOrNull()?.let {
                    processSpeech(it)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("SpeechResolversVM", "onPartialResults: $matches")
                matches?.firstOrNull()?.let {
                    _speech.value = it
                    _partialSpeech.value = it
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        with(getApplication<Application>().applicationContext) {
            ServiceUtils.startService(
                this,
                Intent(this, AudioControlService::class.java)
            )
        }
        viewModelScope.launch {
            AudioControlService.mediaButtonPresses.collectLatest {
                Log.i("SpeechResolversVM", "media button collected")
                _triggerListening.emit(Unit)
            }
        }
    }

    fun toggleListening(locale: Locale = Locale.getDefault()) =
        if (_isListening.value == true) stopListening()
        else startListening(locale)

    private fun startListening(locale: Locale) {
        val languageTag = if (locale.language == "he") "iw-IL" else locale.toLanguageTag()
        Log.d("SpeechResolversVM", "startListening: locale=$locale, using tag=$languageTag")
        playSfx(SFXManager.SFX.NOTIFY_INFO)
        _isListening.value = true
        speechRecognizer.startListening(
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    getApplication<Application>().packageName
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            })
    }

    private fun stopListening() {
        speechRecognizer.stopListening()
        _isListening.value = false
    }

    fun processSpeech(spokenText: String, locale: Locale? = null) {
        spokenText.let { s ->
            _speech.postValue(s)
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
        speechRecognizer.destroy()
        ServiceUtils.stopService(
            getApplication<Application>().applicationContext,
            AudioControlService::class.java
        )
        resolvers.keys.forEach {
            if (it is Closeable)
                it.close()
        }
    }
}
