package dji.sampleV5.aircraft.pages

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.dr.vocom.CommandController
import com.dr.vocom.LocaleUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVirtualStickPageVocomBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.LiveStreamVM
import dji.sampleV5.aircraft.models.SimulatorVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.virtualstick.AircraftController
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.callback.CommonCallbacks.CompletionCallback
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.isKeySupported
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Locale
import kotlin.math.abs

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/5/11
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickFragmentVoCom : DJIFragment() {
    private val locale = Locale("he", "IL")

    private val intelligentFlightVM: IntelligentFlightVM by activityViewModels()
    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val liveStreamVM: LiveStreamVM by activityViewModels()
    private lateinit var controller: AircraftController
    private lateinit var commandController: CommandController

    private var binding: FragVirtualStickPageVocomBinding? = null

    private val deviation: Double = 0.02

    private lateinit var svCameraStream: SurfaceView
    private var cameraStreamSurface: Surface? = null
    private var cameraStreamWidth: Int = -1
    private var cameraStreamHeight: Int = -1
    private var cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    private var cameraStreamScaleType: ICameraStreamManager.ScaleType =
        ICameraStreamManager.ScaleType.CENTER_INSIDE
    private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager

    private lateinit var locationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationManager: LocationManager
    private lateinit var locationCallback: LocationCallback
    private var currentDeviceLocation: Location? = null
    private var isRequestingLocationUpdates = false
    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                ToastUtils.showToast("Location permission granted")
                startLocationUpdates()
            } else {
                // Explain to the user that the feature is unavailable because the
                // features requires a permission that the user has denied.
                ToastUtils.showToast("Location permission denied, Follow Me feature unavailable.")
            }
        }
    private val enableLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User has returned from the location settings screen.
        // We can optionally re-check if location is enabled and try to start updates.
        Log.d("LocationSettings", "Returned from location settings.")
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) startLocationUpdates()
        }
    }

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenText = result.data!!
                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.get(0)

            if (spokenText != null) onHearText(spokenText)
            else binding?.txtSpeechResult?.text = getString(R.string.error_speech_unrecognised)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val config = Configuration(requireContext().resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val localInflater =
            inflater.cloneInContext(requireContext().createConfigurationContext(config))

        binding = FragVirtualStickPageVocomBinding.inflate(localInflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initController()

        binding?.widgetHorizontalSituationIndicator?.setSimpleModeEnable(false)
        initBtnClickListener()
        initStickListener()

        initMicListener()

        svCameraStream = view.findViewById(R.id.sv_camera_stream)
        initCameraStreamSurfaceCallback()

        locationProviderClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        locationManager = requireActivity().getSystemService(Context.LOCATION_SERVICE)
                as LocationManager
        locationRequest = LocationRequest.Builder(10000) // 10 seconds
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5000) // 5 seconds
            .setMaxUpdateDelayMillis(15000) // 15 seconds
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult
                for (location in locationResult.locations) {
                    currentDeviceLocation = location
                    Log.d("DeviceLocation", "Lat: ${location.latitude}, Lon: ${location.longitude}")
                }
            }
        }

        binding?.btnStop?.setOnClickListener { controller.stop() }
        binding?.btnFollow?.setOnClickListener {
            if (!(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                ))
            ) {
                ToastUtils.showToast("Please enable location services.")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                enableLocationLauncher.launch(intent)
                return@setOnClickListener
            }

            if (!isRequestingLocationUpdates) startLocationUpdates()

            if (currentDeviceLocation == null) {
                ToastUtils.showToast("Device location not yet available. Waiting for updates.")
                Log.d("FollowMeLocation", "Location not available yet.")
                return@setOnClickListener
            }

            val locationMsg =
                "Current Device Location: Lat: ${currentDeviceLocation!!.latitude}, Lon: ${currentDeviceLocation!!.longitude}"
            Log.d("FollowMeLocation", locationMsg)
            ToastUtils.showToast(locationMsg)
            controller.flyTo(
                LocationCoordinate2D(
                    currentDeviceLocation!!.latitude,
                    currentDeviceLocation!!.longitude
                )
            )
        }

        virtualStickVM.listenRCStick()
        virtualStickVM.currentSpeedLevel.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.useRcStick.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.stickValue.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.virtualStickAdvancedParam.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.text = it
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller.destroy()
        if (cameraStreamSurface != null) {
            cameraStreamManager.removeCameraStreamSurface(cameraStreamSurface!!)
            cameraStreamSurface = null
        }
        binding = null
    }

    override fun onResume() {
        super.onResume()
        if (!isRequestingLocationUpdates) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startLocationUpdates()
            } else {
                requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtils.showToast("Location permission not granted. Cannot start updates.")
            return
        }
        locationProviderClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        isRequestingLocationUpdates = true
        Log.d("DeviceLocation", "Started location updates")
    }

    private fun stopLocationUpdates() {
        if (isRequestingLocationUpdates) {
            locationProviderClient.removeLocationUpdates(locationCallback)
            isRequestingLocationUpdates = false
            Log.d("DeviceLocation", "Stopped location updates")
        }
    }

    private fun initCameraStreamSurfaceCallback() {
        svCameraStream.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("CameraView", "Surface Created")
                cameraStreamSurface = holder.surface
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.d("CameraView", "Surface Changed: width=$width, height=$height")
                cameraStreamWidth = width
                cameraStreamHeight = height
                cameraStreamSurface = holder.surface // Update surface reference if it changed
                putCameraStreamSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d("CameraView", "Surface Destroyed")
                cameraStreamManager.removeCameraStreamSurface(holder.surface)
                cameraStreamSurface = null // Clear the reference
            }
        })
    }

    private fun putCameraStreamSurface() {
        if (cameraStreamSurface == null || cameraStreamWidth == -1 || cameraStreamHeight == -1) {
            Log.w("CameraView", "Cannot put camera stream surface, not fully initialized.")
            return
        }
        Log.d("CameraView", "Putting camera stream surface for camera: $cameraIndex")
        cameraStreamManager.putCameraStreamSurface(
            cameraIndex,
            cameraStreamSurface!!,
            cameraStreamWidth,
            cameraStreamHeight,
            cameraStreamScaleType
        )
    }

    private fun updateVirtualStickInfo() {
        val builder = StringBuilder()
        builder.append("Spees level:").append(virtualStickVM.currentSpeedLevel.value)
        builder.append("\n")
        builder.append("Use rc stick as virtual stick:").append(virtualStickVM.useRcStick.value)
        builder.append("\n")
        builder.append("Is virtual stick enable:")
            .append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable)
        builder.append("\n")
        builder.append("Current control permission owner:")
            .append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.currentFlightControlAuthorityOwner)
        builder.append("\n")
        builder.append("Change reason:")
            .append(virtualStickVM.currentVirtualStickStateInfo.value?.reason)
        builder.append("\n")
        builder.append("Rc stick value:").append(virtualStickVM.stickValue.value?.toString())
        builder.append("\n")
        builder.append("Is virtual stick advanced mode enable:")
            .append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled)
        builder.append("\n")
        builder.append("Virtual stick advanced mode param:")
            .append(virtualStickVM.virtualStickAdvancedParam.value?.toJson())
        builder.append("\n")
        mainHandler.post {
            binding?.virtualStickInfoTv?.text = builder.toString()
        }
    }

    private fun initController() {
        controller = AircraftController(
            virtualStickVM,
            basicAircraftControlVM,
            intelligentFlightVM
        )
        commandController =
            CommandController(CommandController.ParseConfig())
        commandController.commands.addAll(
            arrayOf(
                CommandController.Command(
                    "STOP",
                    R.string.commands_stop
                ) { controller.stop() },
                CommandController.Command(
                    "TAKE OFF",
                    R.string.commands_takeoff
                ) {
                    controller.takeoff()
                },
                CommandController.Command(
                    "LAND",
                    R.string.commands_land
                ) { controller.land() },
                CommandController.Command(
                    "RETURN HOME",
                    R.string.commands_return_home
                ),
                CommandController.Command(
                    "FOLLOW TARGET",
                    R.string.commands_follow_target
                ),
                CommandController.Command("FOLLOW ME", R.string.commands_follow_me),
                CommandController.Command(
                    "FLY WAYPOINT",
                    R.string.commands_fly_waypoint
                ),

                CommandController.Command(
                    "ASCEND",
                    R.string.command_up
                ) { controller.ascendBy(1.0, .1) },
                CommandController.Command(
                    "SCAN",
                    R.string.commands_scan_forward
                ) { controller.forwardBy(2.0, .1) },

                CommandController.Command("STEALTH", R.string.commands_silence),
            )
        )

        if (binding?.leftStickView != null && binding?.rightStickView != null)
            controller.attachOnScreenSticks(
                binding?.leftStickView!!, binding?.rightStickView!!,
                object : CompletionCallback {
                    override fun onSuccess() {
                        ToastUtils.showToast("sticks set")
                    }

                    override fun onFailure(error: IDJIError) {
                        ToastUtils.showToast("error setting sticks: ${error.errorCode()}")
                    }
                },
                deviation = deviation
            )
    }

    private fun initBtnClickListener() {
        binding?.btnEnableVirtualStick?.setOnClickListener {
            val keys = arrayOf(
                FlightControllerKey.KeyAircraftLocation.create(),
                FlightControllerKey.KeyGPSIsValid.create(),
                FlightControllerKey.KeyGPSSignalLevel.create()
            )
            var str = ""
            keys.forEach {
                if (!it.isKeySupported())
                    Log.e("DJI", "Key ${it.keyIdentifier} isn't supported for some ungodly reason")
                val value = it.get()
                if (value != null) {
                    Log.d("DJI", "${it.keyIdentifier}: $value")
                    str += "${it.keyIdentifier}: $value"
                } else {
                    Log.e("DJI", "Couldn't get ${it.keyIdentifier}!")
                    str += "Couldn't get ${it.keyIdentifier}!"
                }
                str += "\n"
            }
            binding?.locationInfoTv?.text = str

            virtualStickVM.enableVirtualStick(object : CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("snees.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("snoss.. ${error.errorCode()},${error.innerCode()}")
                }
            })
        }
        binding?.btnDisableVirtualStick?.setOnClickListener {
            virtualStickVM.disableVirtualStick(object : CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("sdos.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("stass.... ${error})")
                }
            })
        }

        binding?.btnDisableSim?.setOnClickListener { disableSimulator(null) }
        binding?.btnEnableSim?.setOnClickListener { enableSimulator() }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.apply {
                text = it
                setTextColor(if (simulatorVM.isSimulatorOn()) Color.BLACK else Color.RED)
            }
        }
        controller.location.observeForever {
            binding?.locationInfoTv?.text = it.toString()
        }

        binding?.btnSetVirtualStickSpeedLevel?.setOnClickListener {
            val speedLevels = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
            initPopupNumberPicker(Helper.makeList(speedLevels)) {
                virtualStickVM.setSpeedLevel(speedLevels[indexChosen[0]])
                resetIndex()
            }
        }
        binding?.btnTakeOff?.setOnClickListener {
            basicAircraftControlVM.startTakeOff(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start takeOff onSuccess.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start takeOff onFailure,$error")
                }
            })
        }
        binding?.btnLanding?.setOnClickListener {
            basicAircraftControlVM.startLanding(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start landing onSuccess.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start landing onFailure,$error")
                }
            })
        }
    }

    private fun initStickListener() {
        binding?.leftStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var leftPx = 0F
                var leftPy = 0F

                if (abs(pX) >= deviation) leftPx = pX
                if (abs(pY) >= deviation) leftPy = pY

                virtualStickVM.setLeftPosition(
                    (leftPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (leftPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        binding?.rightStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var rightPx = 0F
                var rightPy = 0F

                if (abs(pX) >= deviation) rightPx = pX
                if (abs(pY) >= deviation) rightPy = pY

                virtualStickVM.setRightPosition(
                    (rightPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (rightPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
    }

    private fun initMicListener() {
        binding?.btnMic?.setOnClickListener { startListening() }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.speech_prompt_listening)
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val bias = commandController.commands
                    .flatMap {
                        it.strings(
                            LocaleUtils.getLocalizedResources(
                                requireContext(),
                                locale
                            )
                        )
                    }
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
            }
        }

        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            binding?.txtSpeechResult?.text =
                getString(R.string.mission_edit_warning_unsupport_action)
        }
    }

    private fun onHearText(spokenText: String) {
        val com = commandController.resolve(
            spokenText,
            LocaleUtils.getLocalizedResources(requireContext(), locale)
        )

        binding?.commandResult?.text =
            if (com == null) requireContext().getString(R.string.error_speech_unrecognised)
            else {
                com.func()
                com.name
            }
    }

    private fun enableSimulator() {
        val coordinate2D = LocationCoordinate2D()
        val data = InitializationSettings.createInstance(coordinate2D, 3)
        simulatorVM.enableSimulator(data, object : CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("start Success")
                mainHandler.post {
                    binding?.simulatorStateInfoTv?.setTextColor(Color.BLACK)
                }
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("start Failed" + error.description())
            }
        })
    }

    private fun disableSimulator(callbacks: CompletionCallback?) {
        simulatorVM.disableSimulator(object : CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("disable Success")
                mainHandler.post { binding?.simulatorStateInfoTv?.setTextColor(Color.RED) }
                callbacks?.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("close Failed" + error.description())
                callbacks?.onFailure(error)
            }
        })
    }
}
