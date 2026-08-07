package com.kcg.dr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.kcg.dr.api.ApiServerVM
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.location.DeviceLocationViewModel
import com.kcg.dr.location.LiveLocationProvider
import com.kcg.dr.utils.TTSManager.speak
import com.kcg.dr.utils.asDjiLocation
import com.kcg.dr.voice.CommandResolver.Command
import com.kcg.dr.voice.RegexCommandResolver
import com.kcg.dr.voice.VoiceVM
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomContainerBinding
import dji.sampleV5.aircraft.models.RecordingVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.pages.DJIFragment

class VoComContainerFragment : DJIFragment() {
    private var _binding: FragVocomContainerBinding? = null
    private val binding get() = _binding!!

    private val recordingVM: RecordingVM by activityViewModels()
    private val voiceVM: VoiceVM by activityViewModels({
        MutableCreationExtras(defaultViewModelCreationExtras).apply {
            set(VoiceVM.RES_LIST_KEY, listOf(commandResolver))
        }
    }, { VoiceVM.Factory })

    private val apiVM: ApiServerVM by activityViewModels({
        MutableCreationExtras(defaultViewModelCreationExtras).apply {
            set(ApiServerVM.CONTROLLER_KEY, aircraftControlVM.controller)
        }
    }, { ApiServerVM.Factory })
    private lateinit var commandResolver: RegexCommandResolver
    // Original DJI ViewModels needed for controller init
    private val aircraftControlVM: AircraftControlVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(AircraftControlVM.STICK_VM_KEY, virtualStickVM)
            }
        },
        { AircraftControlVM.Factory }
    )

    private val deviceLocationVM: DeviceLocationViewModel by activityViewModels()
    private val locationProvider = LiveLocationProvider(this, 200, 50, 500)

    private val virtualStickVM: VirtualStickVM by activityViewModels()

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

        commandResolver = RegexCommandResolver(requireContext())
        commandResolver.setCommands(
            listOf(
                Command(
                    R.string.command_hello,
                ) {
                    speak("Hello there!")
                    aircraftControlVM.controller.fly { wave() }
                },
                Command(
                    R.string.commands_spin,
                ) {
                    speak("Wheeee Heee eeeeee whhheeee whheee")
                    aircraftControlVM.controller.fly { spinBy(720.0, velocity = 180.0) }
                },
            )
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
                    deviceLocationVM.location.postValue(location.asDjiLocation())
            }
        }
        locationProvider.startRequesting()

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