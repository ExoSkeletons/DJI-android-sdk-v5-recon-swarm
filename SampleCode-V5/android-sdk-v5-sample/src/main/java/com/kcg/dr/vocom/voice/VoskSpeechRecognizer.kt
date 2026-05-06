package com.kcg.dr.vocom.voice

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale

class VoskSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val sampleRate: Int = 16000,
    private val silenceTimeoutMs: Long = 2000L,   // stop after 2 seconds silence
) {
    private val modelCache = mutableMapOf<String, Model>()
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var silenceJob: Job? = null

    suspend fun preloadAll() {
        val assetManager = context.assets
        val modelLangDirs = assetManager.list("models") ?: emptyArray()

        for (lang in modelLangDirs) {
            if (!modelCache.containsKey(lang)) {
                val fsPath = unpackAssetFolder("models/$lang")
                modelCache[lang] = Model(fsPath)
            }
        }
    }

    private suspend fun unpackAssetFolder(assetFolder: String): String = coroutineScope {
        val outDir = File(context.filesDir, assetFolder)
        if (outDir.exists()) return@coroutineScope outDir.absolutePath

        outDir.mkdirs()

        val assetManager = context.assets
        val files = assetManager.list(assetFolder) ?: emptyArray()

        for (file in files) {
            val fullAssetPath = "$assetFolder/$file"
            val outFile = File(outDir, file)

            if (assetManager.list(fullAssetPath)?.isNotEmpty() == true) {
                // recursively unpack subfolders
                unpackAssetFolder(fullAssetPath)
            } else {
                assetManager.open(fullAssetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        return@coroutineScope outDir.absolutePath
    }

    private fun getModel(locale: Locale): Model {
        val lang = locale.language.lowercase()

        return modelCache[lang]
            ?: throw IllegalArgumentException("No Vosk model for locale '$lang' in assets/models/")
    }

    private val utteranceBuffer = mutableListOf<String>()

    interface SpeechListener {
        fun onPartial(text: String) {}
        fun onUtterance(text: String) {}          // Vosk “final” chunks
        fun onSpeechEnded(fullText: String) {}   // Combined final speech when silence stops
    }

    private var speechListener: SpeechListener? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        locale: Locale,
        listener: SpeechListener,
    ) {
        val model = getModel(locale)
        recognizer = Recognizer(model, sampleRate.toFloat())
        speechListener = listener

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        audioRecord!!.startRecording()

        // Reset silence timer
        resetSilenceTimer()

        scope.launch {
            val buffer = ByteArray(4096)

            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord!!.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val rec = recognizer!!

                    // --- Partial result
                    val partialJson = rec.partialResult
                    val partialText = extractText(partialJson)
                    if (!partialText.isNullOrBlank()) {
                        speechListener?.onPartial(partialText)
                    }

                    // --- Final/utterance result
                    if (rec.acceptWaveForm(buffer, read)) {
                        val json = rec.result
                        val text = extractText(json)
                        if (!text.isNullOrBlank()) {
                            utteranceBuffer.add(text)
                            speechListener?.onUtterance(text)
                            resetSilenceTimer()   // reset silence timer on detected speech
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        recognizer?.close()
        recognizer = null

        // Combine all utterances and fire the full speech callback
        if (utteranceBuffer.isNotEmpty()) {
            val fullText = utteranceBuffer.joinToString(" ")
            speechListener?.onSpeechEnded(fullText)
            utteranceBuffer.clear()
        }
    }

    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(silenceTimeoutMs)
            stop()
        }
    }

    private fun extractText(json: String?): String? {
        if (json == null) return null
        return JSONObject(json).optString("text", "")
    }
}
