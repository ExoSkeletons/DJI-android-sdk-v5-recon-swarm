package com.kcg.dr.flight

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.kcg.dr.location.UserVM
import com.kcg.dr.utils.CoroutineUtils.observe
import com.kcg.dr.utils.LocationUtils.bearingTo
import com.kcg.dr.utils.LocationUtils.distanceTo
import com.kcg.dr.utils.as2D
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomStatusBinding
import dji.sampleV5.aircraft.models.VirtualStickVM
import kotlin.math.roundToInt

class StatusFragment : Fragment() {
    private var _binding: FragVocomStatusBinding? = null
    private val binding get() = _binding!!

    private val virtualStickVM: VirtualStickVM by activityViewModels()

    private val aircraftVM: AircraftControlVM by activityViewModels({
        MutableCreationExtras(defaultViewModelCreationExtras).apply {
            set(AircraftControlVM.STICK_VM_KEY, virtualStickVM)
        }
    }, { AircraftControlVM.Factory })
    private val deviceLocationVM: UserVM by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceLocationVM.location.observe(viewLifecycleOwner) { loc ->
            binding.tvLocationDevice.text = loc?.let {
                String.format(
                    getString(R.string.location_fmt_short),
                    it.latitude,
                    it.longitude,
                    it.altitude
                )
            } ?: "-"
        }

        aircraftVM.apply {
            aircraftLocation.observe(viewLifecycleOwner) { loc ->
                binding.tvLocationAircraft.text = loc?.let {
                    String.format(
                        getString(R.string.location_fmt_short),
                        it.latitude,
                        it.longitude,
                        it.altitude
                    )
                } ?: "-"
            }
            attitude.observe(viewLifecycleOwner) {
                binding.tvAttitude.text = it?.toJson()?.toString() ?: "-"
            }
            aircraftHeight.observe(viewLifecycleOwner) {
                binding.tvAircraftHeight.text = it?.let { String.format("%.1f", it) } ?: "-"
            }
            batteryPercent.observe(viewLifecycleOwner) {
                binding.tvBatteryPercent.text = it?.let { "$it%" } ?: "-"
            }
        }

        // relations between aircraft location and device location
        aircraftVM.apply {
            aircraftLocation.observe(viewLifecycleOwner) { aircraft ->
                val device = deviceLocationVM.location.value

                val dist = aircraft?.let { device?.let { aircraft.distanceTo(device) } }
                val dist2D = aircraft?.let { device?.let { aircraft.as2D.distanceTo(device.as2D) } }
                val angleTo = aircraft?.let {
                    device?.let {
                        heading.value?.let { aircraft.as2D.bearingTo(device.as2D) - it }
                    }
                }

                binding.tvDistance.text = dist?.let { "${it}m" } ?: "-"
                binding.tvDistance2D.text = dist2D?.let { "${it}m" } ?: "-"
                binding.tvAngleTo.text = angleTo?.let { "${it.roundToInt()}°" } ?: "-"
            }
        }

        // Setup HSI
        binding.widgetHorizontalSituationIndicator.setSimpleModeEnable(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
