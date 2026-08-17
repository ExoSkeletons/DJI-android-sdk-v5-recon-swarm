package com.kcg.dr.yerut

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.kcg.dr.flight.AircraftControlVM
import dji.sampleV5.aircraft.databinding.FragOscBinding

class OscFragment : Fragment() {
    lateinit var binding: FragOscBinding

    private val oscVM: OscVm by viewModels()
    private val controllerVM: AircraftControlVM by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragOscBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        oscVM.ampH.observe(viewLifecycleOwner) { v ->
            val current = binding.tvAmpH.text.toString().toDoubleOrNull()
            if (v != current)
                binding.tvAmpH.setText(v?.toString() ?: "")
        }
        oscVM.freqH.observe(viewLifecycleOwner) { v ->
            val current = binding.tvFreqH.text.toString().toDoubleOrNull()
            if (v != current)
                binding.tvFreqH.setText(v?.toString() ?: "")
        }

        binding.tvFreqH.addTextChangedListener {
            oscVM.freqH.value = it.toString().toDoubleOrNull()
        }
        binding.tvAmpH.addTextChangedListener {
            oscVM.ampH.value = it.toString().toDoubleOrNull()
        }
        binding.btnGo.setOnClickListener {
            controllerVM.controller.fly {
                val ampH = oscVM.ampH.value ?: throw IllegalArgumentException("ampH is null")
                val freqH = oscVM.freqH.value ?: throw IllegalArgumentException("freqH is null")

                oscillate(ampH, freqH)
            }
        }
    }
}