package com.kcg.dr.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.kcg.dr.flight.AircraftControlVM
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomVoiceControlBinding
import dji.sampleV5.aircraft.databinding.ItemResolverBinding
import dji.sampleV5.aircraft.models.VirtualStickVM
import java.util.Locale

class VoiceControlFragment : Fragment() {
    private var _binding: FragVocomVoiceControlBinding? = null
    private val binding get() = _binding!!

    private val stickVM: VirtualStickVM by activityViewModels()
    private val controllerVM: AircraftControlVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(AircraftControlVM.STICK_VM_KEY, stickVM)
            }
        },
        { AircraftControlVM.Factory }
    )
    private val viewModel: SpeechResloversVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(
                    SpeechResloversVM.RES_LIST_KEY,
                    listOf(
                        // RegexCommandResolver(requireContext()),
                        LlamaActionSequenceResolver(controllerVM.controller, requireContext())
                    )
                )
            }
        },
        { SpeechResloversVM.Factory }
    )
    private val locale = Locale("iw", "IL")

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()

            spokenText?.let { viewModel.processSpeech(it, locale) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomVoiceControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnMic.setOnClickListener { startListening() }

        viewModel.speechText.observe(viewLifecycleOwner) {
            binding.speech.text = it
        }
        viewModel.resolutionName.observe(viewLifecycleOwner) {
            binding.sttResult.text = it
        }
        viewModel.resolverStatuses.observe(viewLifecycleOwner) { statuses ->
            updateResolverUI(statuses)
        }
    }

    private val rowBinds = mutableListOf<ItemResolverBinding>()

    private fun updateResolverUI(statuses: List<SpeechResloversVM.ResolverStatus>) {
        if (rowBinds.size != statuses.size) {
            binding.tlResolvers.removeAllViews()
            rowBinds.clear()
            statuses.forEach { status ->
                val rowBinding =
                    ItemResolverBinding.inflate(layoutInflater, binding.tlResolvers, true)
                rowBinding.tvName.text = status.name
                rowBinds.add(rowBinding)
            }
        }

        statuses.forEachIndexed { index, status ->
            val rBind = rowBinds[index]
            rBind.root.alpha = if (status.state == SpeechResloversVM.State.IDLE) 0.5f else 1.0f

            when (status.state) {
                SpeechResloversVM.State.IDLE -> {
                    rBind.prog.visibility = View.GONE
                    rBind.ivStatus.visibility = View.GONE
                }

                SpeechResloversVM.State.ACTIVE -> {
                    rBind.prog.visibility = View.VISIBLE
                    rBind.ivStatus.visibility = View.GONE
                }
            }
            status.result?.takeIf { it.isSuccess }
                ?.let {
                    rBind.prog.visibility = View.GONE
                    rBind.ivStatus.visibility = View.VISIBLE
                    rBind.ivStatus.setImageResource(R.drawable.uxsdk_ic_alert_good)
                    // rBind.tvResult = it?.getOrNull()
                }
                ?: run {
                    rBind.prog.visibility = View.GONE
                    rBind.ivStatus.visibility = View.VISIBLE
                    rBind.ivStatus.setImageResource(R.drawable.uxsdk_ic_cancel_landing_disabled)
                }
        }
    }

    private fun startListening(locale: Locale = this.locale) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt_listening))
        }

        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("VoiceControlFragment", "Failed to start speech recognition", e)
            binding.speech.text = getString(R.string.dji_msdk_error_common_unsupported)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
