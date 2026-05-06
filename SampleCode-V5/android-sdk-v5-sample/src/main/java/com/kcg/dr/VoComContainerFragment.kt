package com.kcg.dr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.kcg.dr.flight.AircraftControlViewModel
import dji.sampleV5.aircraft.databinding.FragVocomContainerBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.CameraGimbalVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sampleV5.aircraft.pages.DJIFragment

class VoComContainerFragment : DJIFragment() {
    private var _binding: FragVocomContainerBinding? = null
    private val binding get() = _binding!!

    private val aircraftControlVM: AircraftControlViewModel by activityViewModels()

    // Original DJI ViewModels needed for controller init
    private val intelligentFlightVM: IntelligentFlightVM by activityViewModels()
    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val cameraGimbalVM: CameraGimbalVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val wayPointV3VM: WayPointV3VM by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the shared controller in the ViewModel
        aircraftControlVM.initController(
            virtualStickVM,
            basicAircraftControlVM,
            cameraGimbalVM,
            intelligentFlightVM,
            wayPointV3VM
        )

        // The child fragments are declared in the XML layout via FragmentContainerView
        // and will automatically be instantiated and added.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}