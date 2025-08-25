package com.dr.vocom

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVirtualStickPageVocomBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.LiveStreamVM
import dji.sampleV5.aircraft.models.SimulatorVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sampleV5.aircraft.pages.DJIFragment
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.isKeySupported
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.StreamQuality
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
    private val wayPointV3VM: WayPointV3VM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val liveStreamVM: LiveStreamVM by activityViewModels()
    private lateinit var controller: AircraftController
    private lateinit var commandResolver: CommandResolver

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

    private val liveLocation: LiveLocationProvider = LiveLocationProvider(this)
    private var liveLocationRequired = false
    private var currentDeviceLocation: Location? = null

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
        initLiveStreamControls()

        liveLocation.init(requireContext())
        liveLocation.locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    currentDeviceLocation = location
                    binding?.tvLocationDevice?.text =
                        getString(
                            R.string.location_fmt_short,
                            location.latitude,
                            location.longitude,
                            location.altitude
                        )
                    Log.d("DeviceLocation", "Lat: ${location.latitude}, Lon: ${location.longitude}")
                }
            }
        }

        binding?.btnStop?.setOnClickListener { controller.stop() }
        binding?.btnFollow?.setOnClickListener {
            liveLocationRequired = true
            if (!liveLocation.enabled()) {
                liveLocation.enable()
                return@setOnClickListener
            }

            val lat = currentDeviceLocation!!.latitude
            val lng = currentDeviceLocation!!.longitude
            controller.flyTo(LocationCoordinate2D(lat, lng))
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
        if (liveStreamVM.isStreaming())
            liveStreamVM.stopStream(null)

        controller.destroy()
        liveLocationRequired = false
        liveLocation.disable()
        if (cameraStreamSurface != null) {
            cameraStreamManager.removeCameraStreamSurface(cameraStreamSurface!!)
            cameraStreamSurface = null
        }
        binding = null
    }

    override fun onResume() {
        super.onResume()
        if (liveLocationRequired) liveLocation.enable() // re-enable location requesting if necessary
    }

    override fun onPause() {
        super.onPause()
        liveLocation.disable() // disable location requesting to conserve battery
    }


    private fun initLiveStreamControls() {
        binding?.btnStartStream?.setOnClickListener {
            val factory = LayoutInflater.from(requireContext())
            val rtmpConfigView = factory.inflate(R.layout.dialog_livestream_rtmp_config_view, null)
            val etRtmpUrl = rtmpConfigView.findViewById<EditText>(R.id.et_livestream_rtmp_config)
            val configDialog = requireContext().let {
                AlertDialog.Builder(it, R.style.Base_ThemeOverlay_AppCompat_Dialog_Alert)
                    .setIcon(android.R.drawable.ic_menu_camera)
                    .setTitle(R.string.live_share_rtmp_type_name)
                    .setCancelable(false)
                    .setView(rtmpConfigView)
                    .setPositiveButton(R.string.ad_confirm) { configDialog, _ ->
                        val rtmpUrl = etRtmpUrl.text.toString()
                        if (TextUtils.isEmpty(rtmpUrl)) {
                            ToastUtils.showToast("input is empty")
                        } else {
                            liveStreamVM.setRTMPConfig(rtmpUrl)

                            liveStreamVM.setCameraIndex(cameraIndex)
                            liveStreamVM.setLiveVideoBitRateMode(LiveVideoBitrateMode.AUTO)
                            liveStreamVM.setLiveStreamScaleType(ICameraStreamManager.ScaleType.CENTER_CROP)
                            liveStreamVM.setLiveStreamQuality(StreamQuality.SD)

                            liveStreamVM.startStream(object : CommonCallbacks.CompletionCallback {
                                override fun onSuccess() {
                                    ToastUtils.showToast("live stream starting")
                                }

                                override fun onFailure(error: IDJIError) {
                                    activity?.runOnUiThread {
                                        binding?.tvLivestreamStatus?.text =
                                            "Failed to stream to ${liveStreamVM.getRtmpUrl()}"
                                        liveStreamVM.stopStream(null)
                                        ToastUtils.showToast("live stream fail ${error.description()}")
                                    }
                                }
                            })
                        }
                        configDialog.dismiss()
                    }
                    .setNegativeButton(R.string.ad_cancel) { configDialog, _ ->
                        configDialog.dismiss()
                    }
                    .create()
            }
            configDialog.show()
        }

        binding?.btnStopStream?.setOnClickListener {
            if (liveStreamVM.isStreaming())
                liveStreamVM.stopStream(null)
        }

        liveStreamVM.liveStreamStatus.observe(viewLifecycleOwner) { status ->
            binding?.tvLivestreamStatus?.text = "Streaming: ${status?.isStreaming ?: ""}"
            binding?.btnStartStream?.isEnabled = !liveStreamVM.isStreaming()
            binding?.btnStopStream?.isEnabled = liveStreamVM.isStreaming()
        }

        liveStreamVM.liveStreamError.observe(viewLifecycleOwner) { error ->
            error?.let {
                ToastUtils.showToast("Live Stream Error: ${it.description()}")
                Log.e("LiveStream", "Error: ${it.description()}")
            }
        }
    }

    private fun initCameraStreamSurfaceCallback() {
        svCameraStream.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("CameraView", "Surface Created")
                cameraStreamSurface = holder.surface
                if (cameraStreamWidth != -1 && cameraStreamHeight != -1) { // Added this check from previous suggestions
                    putCameraStreamSurface()
                }
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
                // Check if cameraStreamSurface is not null before removing, good practice
                if (cameraStreamSurface != null) {
                    cameraStreamManager.removeCameraStreamSurface(holder.surface)
                }
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
            intelligentFlightVM,
            wayPointV3VM
        )
        virtualStickVM.stickValue.observe(viewLifecycleOwner) { stickValue ->
            if (virtualStickVM.currentVirtualStickStateInfo.value!!.state.isVirtualStickEnable) {
                ToastUtils.showShortToast(
                    "touchie da sticks!\n" +
                            "lh=${stickValue.leftHorizontal} lh=${stickValue.leftVertical}" +
                            " rh=${stickValue.rightHorizontal} rh=${stickValue.rightVertical}"
                )
                controller.stop(returnStickControl = true) // stop controller return control to manual RC
            }
        }
        controller.activate(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("controller activated successfully")

                initVoiceCommandResolver()

                if (binding?.leftStickView != null && binding?.rightStickView != null)
                    controller.attachOnScreenSticks(
                        binding?.leftStickView!!, binding?.rightStickView!!,
                        object : CommonCallbacks.CompletionCallback {
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

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("error activating controller: ${error.errorCode()}")
            }
        })
    }

    private fun initVoiceCommandResolver() {
        commandResolver =
            CommandResolver(CommandResolver.ParseConfig())
        commandResolver.commands.addAll(
            arrayOf(
                CommandResolver.Command(
                    "STOP",
                    R.string.commands_stop
                ) { controller.stop() },
                CommandResolver.Command(
                    "TAKE OFF",
                    R.string.commands_takeoff
                ) {
                    controller.takeoff()
                },
                CommandResolver.Command(
                    "LAND",
                    R.string.commands_land
                ) { controller.land() },
                CommandResolver.Command(
                    "RETURN HOME",
                    R.string.commands_return_home
                ),
                CommandResolver.Command(
                    "FOLLOW TARGET",
                    R.string.commands_follow_target
                ),
                CommandResolver.Command("FOLLOW ME", R.string.commands_follow_me),
                CommandResolver.Command(
                    "FLY WAYPOINT",
                    R.string.commands_fly_waypoint
                ),

                CommandResolver.Command(
                    "ASCEND",
                    R.string.command_go_up
                ) { controller.ascendBy(1.0) },
                CommandResolver.Command(
                    "DESCEND",
                    R.string.command_go_down
                ) { controller.ascendBy(-1.0) },
                CommandResolver.Command(
                    "SCAN",
                    R.string.command_go_forward
                ) { controller.forwardBy(1.0) },
                CommandResolver.Command(
                    "BACK UP",
                    R.string.command_go_backward
                ) { controller.forwardBy(-0.5) },
                CommandResolver.Command(
                    "LEFT",
                    R.string.command_go_left
                ) { controller.leftBy(0.5) },
                CommandResolver.Command(
                    "RIGHT",
                    R.string.command_go_right
                ) { controller.leftBy(-0.5) },

                CommandResolver.Command("STEALTH", R.string.commands_silence),
            )
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
            binding?.tvLocationAircraft?.text = str

            virtualStickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("snees.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("snoss.. ${error.errorCode()},${error.innerCode()}")
                }
            })
        }
        binding?.btnDisableVirtualStick?.setOnClickListener {
            virtualStickVM.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("sdos.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("stass.... ${error}")
                }
            })
        }

        binding?.btnDisableSim?.setOnClickListener { disableSimulator(null) }
        binding?.btnEnableSim?.setOnClickListener { enableSimulator() }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.apply {
                text = it
                setTextColor(if (simulatorVM.isSimulatorOn()) Color.WHITE else Color.RED)
            }
        }
        controller.location.observeForever {
            binding?.tvLocationAircraft?.text = getString(
                R.string.location_fmt_short,
                it.latitude,
                it.longitude,
                it.altitude
            )
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
        val maxSilenceDurationMillis = 20000L
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt_listening))
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                maxSilenceDurationMillis
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                maxSilenceDurationMillis
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val bias = commandResolver.commands
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
        } catch (_: Exception) {
            binding?.txtSpeechResult?.text =
                getString(R.string.dji_msdk_error_common_unsupported)
        }
    }

    private fun onHearText(spokenText: String) {
        val com = commandResolver.resolve(
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
        val initLocation = LocationCoordinate2D(
            currentDeviceLocation?.latitude ?: 0.0,
            currentDeviceLocation?.longitude ?: 0.0
        )
        val satelliteCount = 20
        simulatorVM.enableSimulator(
            InitializationSettings.createInstance(initLocation, satelliteCount),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("simulator started")
                    mainHandler.post {
                        binding?.simulatorStateInfoTv?.setTextColor(Color.BLACK)
                    }
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("failed to start simulator" + error.description())
                }
            }
        )
    }

    private fun disableSimulator(callback: CommonCallbacks.CompletionCallback?) {
        simulatorVM.disableSimulator(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("simulator disabled")
                mainHandler.post { binding?.simulatorStateInfoTv?.setTextColor(Color.RED) }
                callback?.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("failed to stop simulator" + error.description())
                callback?.onFailure(error)
            }
        })
    }
}