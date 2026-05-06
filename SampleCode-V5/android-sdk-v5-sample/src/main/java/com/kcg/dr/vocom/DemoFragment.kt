package com.kcg.dr.vocom

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kcg.dr.databinding.FragVocomDemoBinding

class DemoFragment : Fragment() {
    private var _binding: FragVocomDemoBinding? = null
    private val binding get() = _binding!!

    private val demoVM: DemoViewModel by activityViewModels()
    private val voiceVM: VoiceViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragVocomDemoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        demoVM.demoTextIndex.observe(viewLifecycleOwner) { i ->
            val texts = demoVM.demoTexts
            if (i >= 0 && i < texts.size) {
                binding.tvDemoText.text = texts[i]
            }
        }

        binding.btnDemoTextNext.setOnClickListener {
            val next = (demoVM.demoTextIndex.value ?: 0) + 1
            demoVM.demoTextIndex.postValue(next % demoVM.demoTexts.size)
        }

        binding.btnDemoTextPrev.setOnClickListener {
            val prev = (demoVM.demoTextIndex.value ?: 0) - 1
            val size = demoVM.demoTexts.size
            demoVM.demoTextIndex.postValue((prev + size) % size)
        }

        binding.btnDemoTextPlay.setOnClickListener {
            demoVM.nextDemoText()?.let { text ->
                voiceVM.speak(text)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
