package com.kcg.dr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.location.LiveLocationProvider
import com.kcg.dr.location.UserVM
import com.kcg.dr.utils.observe
import com.kcg.dr.utils.asDjiLocation
import dji.sampleV5.aircraft.databinding.FragVocomContainerBinding
import dji.sampleV5.aircraft.pages.DJIFragment
import dji.sdk.keyvalue.value.common.ComponentIndexType

class VoComFragment : DJIFragment() {
    private var _binding: FragVocomContainerBinding? = null
    private val binding get() = _binding!!

    private val deviceLocationVM: UserVM by activityViewModels()
    private val controllerVM: AircraftControlVM by activityViewModels()
    private val locationProvider = LiveLocationProvider(this, 200, 50, 500)


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

        // Start location updates to vm
        locationProvider.init(requireContext())
        locationProvider.locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations)
                    deviceLocationVM.location.tryEmit(location.asDjiLocation())
            }
        }
        locationProvider.startRequesting()

        binding.fpvWidget.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)

        controllerVM.c.vSticks.ownsControl.observe(viewLifecycleOwner) {
            binding.tvControllerOwner.text =
                "Control : " +
                        when (it) {
                            true -> "Auto"
                            else -> "Manual"
                        }
        }

        // Start API server
        //apiVM.startService(notificationVM.controllerChannelId)
        // fixme: this causes a start loop? infinite starts?

        // todo: add follow me and other actions to here
        // todo: replace takeoff/land buttons with dji widgets? keep stop/abort button?
        //  move json fired start/stops to json frag?
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