package com.kcg.dr.vocom

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kcg.dr.LocaleUtils.getLocalizedResources
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomVoiceControlBinding
import java.util.Locale

class VoiceControlFragment : Fragment() {
    private var _binding: FragVocomVoiceControlBinding? = null
    private val binding get() = _binding!!

    private val voiceVM: VoiceViewModel by activityViewModels()
    private val locale = Locale("iw", "IL")

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenText = result.data!!
                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.get(0)

            if (spokenText != null) {
                voiceVM.processSpeech(spokenText, requireContext().getLocalizedResources(locale))
            } else {
                binding.txtSpeechResult.text = getString(R.string.error_speech_unrecognised)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragVocomVoiceControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnMic.setOnClickListener { startListening() }

        voiceVM.speechResult.observe(viewLifecycleOwner) {
            binding.txtSpeechResult.text = it
        }

        voiceVM.commandResult.observe(viewLifecycleOwner) {
            binding.commandResult.text = it
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt_listening))
        }

        try {
            speechRecognizerLauncher.launch(intent)
        } catch (_: Exception) {
            binding.txtSpeechResult.text = getString(R.string.dji_msdk_error_common_unsupported)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
