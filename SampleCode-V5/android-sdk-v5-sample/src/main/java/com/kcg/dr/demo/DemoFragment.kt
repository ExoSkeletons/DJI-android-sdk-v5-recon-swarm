package com.kcg.dr.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kcg.dr.voice.VoiceViewModel
import dji.sampleV5.aircraft.databinding.FragVocomDemoBinding

class DemoFragment : Fragment() {
    private var _binding: FragVocomDemoBinding? = null
    private val binding get() = _binding!!

    private val demoVM: DemoViewModel by activityViewModels()
    private val voiceVM: VoiceViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomDemoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        demoVM.demoTextIndex.observe(viewLifecycleOwner) { i ->
            binding.tvDemoText.text = demoVM.getCurrentText() ?: ""
        }

        binding.btnDemoTextNext.setOnClickListener {
            demoVM.nextText(wrap = true)
        }

        binding.btnDemoTextPrev.setOnClickListener {
            demoVM.prevText(wrap = true)
        }

        binding.btnDemoTextPlay.setOnClickListener {
            demoVM.getCurrentText()?.let { text ->
                voiceVM.speak(text)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
