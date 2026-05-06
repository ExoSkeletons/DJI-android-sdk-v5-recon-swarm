package com.kcg.dr.vocom.flight

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomStatusBinding

class StatusFragment : Fragment() {
    private var _binding: FragVocomStatusBinding? = null
    private val binding get() = _binding!!

    private val aircraftVM: AircraftControlViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragVocomStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        aircraftVM.aircraftLocation.observe(viewLifecycleOwner) { loc ->
            binding.tvLocationAircraft.text = loc?.let {
                String.format(getString(R.string.location_fmt_short), it.latitude, it.longitude, it.altitude)
            } ?: "-"
        }

        aircraftVM.aircraftHeight.observe(viewLifecycleOwner) {
            binding.tvAircraftHeight.text = String.format("%.1f", it)
        }

        aircraftVM.batteryPercent.observe(viewLifecycleOwner) {
            binding.tvBatteryPercent.text = "$it%"
        }
        
        // Setup HSI
        binding.widgetHorizontalSituationIndicator.setSimpleModeEnable(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
