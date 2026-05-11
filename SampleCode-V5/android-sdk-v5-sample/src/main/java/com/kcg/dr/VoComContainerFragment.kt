package com.kcg.dr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.kcg.dr.flight.AircraftControlViewModel
import com.kcg.dr.location.DeviceLocationViewModel
import com.kcg.dr.location.LiveLocationProvider
import dji.sampleV5.aircraft.databinding.FragVocomContainerBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.CameraGimbalVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.RecordingVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sampleV5.aircraft.pages.DJIFragment

class VoComContainerFragment : DJIFragment() {
    private var _binding: FragVocomContainerBinding? = null
    private val binding get() = _binding!!

    private val recordingVM: RecordingVM by activityViewModels()
    private val aircraftControlVM: AircraftControlViewModel by activityViewModels()

    private val deviceLocationVM: DeviceLocationViewModel by activityViewModels()
    private val locationProvider = LiveLocationProvider(this, 200, 50, 500)

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

        // Initialize the controller in the ViewModel
        aircraftControlVM.initController(
            virtualStickVM,
            basicAircraftControlVM,
            cameraGimbalVM,
            intelligentFlightVM,
            wayPointV3VM
        )

        // The child fragments are declared in the XML layout via FragmentContainerView
        // and will automatically be instantiated and added.

        // Observe camera index to update fpv video source widget
        recordingVM.cameraIndex.observe(viewLifecycleOwner, binding.fpvWidget::updateVideoSource)

        // Start location updates to vm
        locationProvider.init(requireContext())
        locationProvider.locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations)
                    deviceLocationVM.location.postValue(location.asDjiLocation)
            }
        }
        locationProvider.startRequesting()
    }

    override fun onPause() {
        super.onPause()
        locationProvider.stopRequesting()
    }

    override fun onResume() {
        super.onResume()
        locationProvider.startRequesting()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        locationProvider.stopRequesting()
    }
}