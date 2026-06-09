package com.kcg.dr.yerut

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.kcg.dr.flight.AircraftControlViewModel
import dji.sampleV5.aircraft.databinding.FragmentOscBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.CameraGimbalVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM

class OscFragment : Fragment() {
    lateinit var binding: FragmentOscBinding

    private val oscVM: OscVm by viewModels()

    private val virtualStickVM: VirtualStickVM by viewModels()
    private val basicAircraftControlVM: BasicAircraftControlVM by viewModels()
    private val cameraGimbalVM: CameraGimbalVM by viewModels()
    private val intelligentFlightVM: IntelligentFlightVM by viewModels()
    private val wayPointV3VM: WayPointV3VM by viewModels()
    private val controllerVM: AircraftControlViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controllerVM.initController(
            virtualStickVM,
            basicAircraftControlVM,
            cameraGimbalVM,
            intelligentFlightVM,
            wayPointV3VM
        )

        oscVM.ampH.observe(this) {
            binding.tvAmpH.setText(it.toString())
        }
        oscVM.freqH.observe(this) {
            binding.tvFreqH.setText(it.toString())
        }

        binding.btnGo.setOnClickListener {
            controllerVM.controller.fly {
                val ampH = oscVM.ampH.value ?: throw IllegalArgumentException("ampH is null")
                val freqH = oscVM.freqH.value ?: throw IllegalArgumentException("freqH is null")

                oscillate(ampH, freqH)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOscBinding.inflate(inflater, container, false)
        return binding.root
    }
}