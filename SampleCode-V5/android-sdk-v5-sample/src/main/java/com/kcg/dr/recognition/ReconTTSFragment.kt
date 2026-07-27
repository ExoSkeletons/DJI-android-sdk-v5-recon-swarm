package com.kcg.dr.recognition

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.kcg.dr.utils.JobRepeater
import com.kcg.dr.utils.SFXManager
import com.kcg.dr.utils.TCPClient
import com.kcg.dr.utils.TCPJSONClient
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class ReconTTSFragment : Fragment() {
    fun Int.round(roundUp: Boolean = false, digitsToKeep: Int): Int {
        val n = this
        if (digitsToKeep <= 0) return 0
        if (n < 10.0.pow(digitsToKeep - 1)) return n

        val totalDigits = log10(n.toDouble()).toInt() + 1
        val power = totalDigits - digitsToKeep
        val scale = 10.0.pow(power).toInt()

        val value = n.toDouble() / scale
        val rounded = if (roundUp) ceil(value) else floor(value)

        return (rounded * scale).toInt()
    }

    private lateinit var stillAlive: JobRepeater

    // TTS
    private val preferredTTSEngine = "com.google.android.tts"
    private lateinit var tts: TextToSpeech
    private val onInitListener = OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            checkAndPromptPreferredTTSEngine()
        }
    }

    private val silent: Boolean = false

    // Obstacle memory
    private val obstacleInfo = MutableLiveData(
        RecognitionMemory.RecognitionSample.ObstacleInfo(
            confidence = 1.0,
            position = listOf(0.0, 0.0),
            inMotion = false
        )
    )
    private val sampleMemory = RecognitionMemory(1.0)
    // Obstacle UI
    private lateinit var oTypeSp: Spinner
    private lateinit var directionSp: Spinner
    private lateinit var editTextObjDist: EditText
    private lateinit var motionSw: SwitchCompat
    private lateinit var editText: EditText
    // Obstacle info connection client
    private lateinit var client: TCPClient
    private lateinit var tvConnectionInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(requireContext(), onInitListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_recon_tts, container, false)

        setupObjectInfoClient()
        // Connect to server UI
        view.findViewById<Button>(R.id.btn_server_info_connect).setOnClickListener {
            try {
                val port = view.findViewById<TextView>(R.id.tv_server_info_port).text.toString()
                val host = view.findViewById<TextView>(R.id.tv_server_info_hostname).text.toString()

                if (!host.isEmpty() && !port.isEmpty())
                    lifecycleScope.launch(Dispatchers.IO) {
                        client.disconnect()
                        client.connect(InetSocketAddress(host, port.toInt()))
                    }
            } catch (e: Exception) {
                tvConnectionInfo.text = e.message
            }
        }
        tvConnectionInfo = view.findViewById(R.id.tv_server_info_msg)

        // Obstacle detection TTS
        view.findViewById<Button>(R.id.speakButtonObj)
            .setOnClickListener { speakInfo(obstacleInfo.value!!) }
        // Obstacle type
        oTypeSp = view.findViewById(R.id.sp_object_type)
        val oTypeSpAd = ArrayAdapter(
            view.context, android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.obstacle_type_values)
        )
        oTypeSpAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        oTypeSp.adapter = oTypeSpAd
        oTypeSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = RecognitionMemory.RecognitionSample.ObstacleInfo.ObstacleType.values()[pos]
                obstacleInfo.value?.type = selected
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Obstacle direction
        directionSp = view.findViewById(R.id.sp_object_direction)
        val directionSpAd = ArrayAdapter(
            view.context, android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.obstacle_direction_values)
        )
        directionSpAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        directionSp.adapter = directionSpAd
        directionSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = RecognitionMemory.RecognitionSample.ObstacleInfo.Direction.values()[pos]
                obstacleInfo.value?.direction = selected
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Obstacle distance
        editTextObjDist = view.findViewById(R.id.et_object_dist)
        editTextObjDist.addTextChangedListener {
            try {
                obstacleInfo.value?.distance = it.toString().toInt()
            } catch (_: NumberFormatException) {
            }
        }
        // Obstacle motion
        motionSw = view.findViewById(R.id.obstacle_motion_sw)
        motionSw.setOnCheckedChangeListener { _, checked ->
            obstacleInfo.value?.inMotion = checked
        }

        obstacleInfo.observe(viewLifecycleOwner) {
            updateObjectInfoView()
        }

        // Free type TTS
        editText = view.findViewById(R.id.tts_free_input_edit_et)
        view.findViewById<Button>(R.id.speakButtonFree).setOnClickListener {
            val text = editText.text.toString()
            speakText(text)
        }


        return view
    }

    override fun onResume() {
        super.onResume()

        // Restart TTS to recheck available voices
        tts.shutdown()
        tts = TextToSpeech(requireContext(), onInitListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts.stop()
        tts.shutdown()
    }

    private fun speakText(text: String) {
        if (text.isNotBlank() && !silent) {
            if (tts.isLanguageAvailable(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) {
                promptInstallTTSLang()
                return
            }
            tts.language = Locale.getDefault()
            tts.setSpeechRate(1.1f)
            SFXManager.playSfx(SFXManager.SFX.NOTIFY_INFO)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun speakInfo(obstacleInfo: RecognitionMemory.RecognitionSample.ObstacleInfo) {
        val text =
            getString(
                if (obstacleInfo.distance >= 10) R.string.obstacle_detected_msg_dist
                else R.string.obstacle_detected_msg,

                resources.getStringArray(R.array.obstacle_type_values)[obstacleInfo.type.ordinal],
                obstacleInfo.distance.round(digitsToKeep = 2),
                resources.getStringArray(R.array.obstacle_direction_values)[obstacleInfo.direction.ordinal],
                resources.getString(if (obstacleInfo.inMotion) R.string.obstacle_moving else R.string.obstacle_stationary)
            )
        if (stillAlive.isActive()) stillAlive.restart()
        speakText(text)
    }

    private fun updateObjectInfoView() {
        obstacleInfo.value?.let {
            oTypeSp.setSelection(it.type.ordinal)
            directionSp.setSelection(it.direction.ordinal)
            editTextObjDist.setText(it.distance.toString())
            motionSw.isChecked = it.inMotion
        }
    }

    private fun setupObjectInfoClient() {
        sampleMemory.apply {
            onSeen = { seen ->
                seen.forEach { obstacle ->
                    // Calculate direction
                    val x = obstacle.position[0]
                    val y = obstacle.position[1]

                    val xThreshold = .5
                    val yThreshold = .45

                    obstacle.direction = when {
                        x < -1 + xThreshold -> RecognitionMemory.RecognitionSample.ObstacleInfo.Direction.Front
                        x > 1 - xThreshold -> RecognitionMemory.RecognitionSample.ObstacleInfo.Direction.Back
                        y < -1 + yThreshold -> RecognitionMemory.RecognitionSample.ObstacleInfo.Direction.Left
                        y > 1 - yThreshold -> RecognitionMemory.RecognitionSample.ObstacleInfo.Direction.Right
                        else -> RecognitionMemory.RecognitionSample.ObstacleInfo.Direction.Away
                    }
                }
            }
            sampleMemory.onMemoryAdded = { newlySeen ->
                newlySeen.firstOrNull()?.let {
                    // Update latest seen obstacle UI
                    obstacleInfo.postValue(it)
                    // Speak about newly seen obstacle
                    speakInfo(it)
                }
            }
        }
        // Socket to get object info from
        client = object : TCPJSONClient<RecognitionMemory.RecognitionSample>(
            publishScope = viewLifecycleOwner.lifecycleScope,
            deserializer = RecognitionMemory.RecognitionSample.serializer()
        ) {
            override fun onConnected(socket: Socket) {
                tvConnectionInfo.text = "connected to ${socket.remoteSocketAddress}!"
                SFXManager.playSfx(SFXManager.SFX.ACTION_CONFIRM)
                stillAlive.restart()
            }

            override fun onReconnectAttempt(delay: Long) {
                tvConnectionInfo.text = "reconncting in ${delay / 1000L}s..."
            }

            override fun onParse(data: RecognitionMemory.RecognitionSample, json: JSONObject) {
                sampleMemory.see(data)
            }

            override fun onError(error: Throwable) {
                Log.e("error", error.message, error)
                tvConnectionInfo.text = "error: ${error.message}"
            }

            override fun onDisconnect() {
                SFXManager.playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                tvConnectionInfo.text = "disconnected"
                stillAlive.cancel()
            }
        }
        stillAlive = JobRepeater(
            timeout = 10_000L,
            repeatTime = 5_000L,
            coroutineScope = lifecycleScope
        ) { SFXManager.playSfx(SFXManager.SFX.NOTIFY_STILL_ALIVE) }
    }

    private fun checkAndPromptPreferredTTSEngine() {
        val currentEngine = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH
        )

        if (currentEngine != null && currentEngine != preferredTTSEngine) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.tts_switch_engine_prompt))
                .setMessage(getString(R.string.tts_switch_engine_prompt_details))
                .setPositiveButton("Open Settings") { dialog, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Unable to open settings",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun promptInstallTTSLang() {
        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        try {
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "No TTS engine available to install language data.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}