package com.kcg.dr.vocom

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.kcg.dr.LiveLocationProvider
import com.kcg.dr.LocationUtils
import com.kcg.dr.LocaleUtils.getLocalizedResources
import com.kcg.dr.LocationUtils.distanceTo
import com.kcg.dr.controller.AircraftController
import com.kcg.dr.remote_api.KeyActivator
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
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs

class VirtualStickFragmentVoCom : DJIFragment() {
    private val locale = Locale("he", "IL")

    private val intelligentFlightVM: IntelligentFlightVM by activityViewModels()
    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val wayPointV3VM: WayPointV3VM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val liveStreamVM: LiveStreamVM by activityViewModels()

    private lateinit var controller: AircraftController
    private val commandResolver: CommandResolver = CommandResolver(CommandResolver.ParseConfig())

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

    private val liveLocation: LiveLocationProvider = LiveLocationProvider(
        this,
        1000,
        500, 5000,
        Priority.PRIORITY_HIGH_ACCURACY
    )
    private var liveLocationRequired = false
    private var deviceLocation: MutableLiveData<LocationCoordinate3D> = MutableLiveData()
    private var aircraftLocation: LocationCoordinate3D? = null

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

    // TTS
    private val preferredTTSEngine = "com.google.android.tts"
    private lateinit var tts: TextToSpeech
    private val onInitListener = OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            checkAndPromptPreferredTTSEngine()
        }
    }
    private var silent: Boolean = false

    // Remote server


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireContext().apply {
            // Server foreground service
            ApiServerService.start(this, 8080)
            // TTS
            tts = TextToSpeech(this, onInitListener)
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
        initVoiceCommandResolver()

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
                    val current = deviceLocation.value
                    val dist = current?.let {
                        LocationCoordinate3D().apply {
                            latitude = location.latitude
                            longitude = location.longitude
                            altitude = location.altitude
                        }.distanceTo(LocationCoordinate3D().apply {
                            latitude = current.latitude
                            longitude = current.longitude
                            altitude = current.altitude
                        })
                    } ?: 0.0

                    // update device location
                    deviceLocation.postValue(LocationCoordinate3D().apply {
                        latitude = location.latitude
                        longitude = location.longitude
                        // altitude = location.altitude
                    })

                    val formattedLocation = getString(
                        R.string.location_fmt_short,
                        location.latitude,
                        location.longitude,
                        location.altitude
                    )
                    binding?.tvLocationDevice?.text = formattedLocation
                    Log.d("DeviceLocation", formattedLocation)
                    Log.d("DeviceLocation", "Distance: ${dist}m")
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

            val location = deviceLocation.value
            if (location == null) return@setOnClickListener

            controller.fly {
                repeat(10) {
                    lookToWithSpin(LocationCoordinate3D().apply {
                        latitude = location.latitude
                        longitude = location.longitude
                        altitude = 0.125
                    })
                    delay(3000)
                }
            }
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

        // restart TTS to recheck available voices
        //  tts.shutdown()
        //  tts = TextToSpeech(requireContext(), onInitListener)
    }

    override fun onPause() {
        super.onPause()
        liveLocation.disable() // disable location requesting to conserve battery
    }

    private fun speakText(text: String) {
        if (text.isNotBlank() && !silent) {
            if (tts.isLanguageAvailable(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) {
                promptInstallTTSLang()
                return
            }
            tts.language = Locale.getDefault()
            tts.setSpeechRate(1.1f)
            SFXManager.playSfx(SFXManager.SFX.NOTIFY_INFO)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun checkAndPromptPreferredTTSEngine() {
        val currentEngine = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH
        )

        if (currentEngine != null && currentEngine != preferredTTSEngine) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.tts_switch_engine_prompt))
                .setMessage(getString(R.string.tts_switch_engine_prompt_details))
                .setPositiveButton("Open Settings") { dialog, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Unable to open settings",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun promptInstallTTSLang() {
        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        try {
            startActivity(installIntent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                "No TTS engine available to install language data.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    fun attachOnScreenSticks(
        leftStk: OnScreenJoystick,
        rightStk: OnScreenJoystick,
        callback: CommonCallbacks.CompletionCallback? = null,
        deviation: Double = 0.02,
        activate: Boolean = true,
    ) {
        val stickVM = virtualStickVM
        leftStk.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var leftPx = 0F
                var leftPy = 0F

                if (abs(pX) >= deviation) leftPx = pX
                if (abs(pY) >= deviation) leftPy = pY

                stickVM.setLeftPosition(
                    (leftPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (leftPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        rightStk.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var rightPx = 0F
                var rightPy = 0F

                if (abs(pX) >= deviation) rightPx = pX
                if (abs(pY) >= deviation) rightPy = pY

                stickVM.setRightPosition(
                    (rightPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (rightPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        if (activate)
            stickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    callback?.onSuccess()
                }

                override fun onFailure(error: IDJIError) {
                    callback?.onFailure(error)
                }
            })
    }

    private fun initController() {
        controller = AircraftController(
            lifecycleScope,

            virtualStickVM,
            basicAircraftControlVM,
            intelligentFlightVM,
            wayPointV3VM
        )
        virtualStickVM.stickValue.observe(viewLifecycleOwner) { stickValue ->
            if (controller.isVirtualStickEnabled()) {
                Log.w("RCSticks", "RC Sticks touched when virtual stick had control")

                ToastUtils.showShortToast("Controller Overriding")
                controller.stop() // stop controller return control to manual RC
            }
        }
        controller.init()
        controller.activate(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("controller activated successfully")

                if (binding?.leftStickView != null && binding?.rightStickView != null)
                    attachOnScreenSticks(
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
        commandResolver.commands.clear()
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
                    controller.fly {
                        val response = KeyActivator.handleKeyRequest(
                            Json.Default.decodeFromString(
                                "{\"group\":\"flight_controller\", \"key\":\"StartTakeoff\"}"
                            )
                        )
                        if (response.ok) Log.d("KeyExecutor", "OK: " + response.result.toString())
                        else Log.e("KeyExecutor", "" + response.error)
                        //takeoff()
                    }
                },
                CommandResolver.Command(
                    "LAND",
                    R.string.commands_land
                ) { controller.fly { land() } },
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
                    "WAVE",
                    R.string.command_gimbal_wave
                ) { controller.fly { gimbalWave() } },
                CommandResolver.Command(
                    "SCAN",
                    R.string.commands_scan
                ) {
                    val scanDist = 2.0
                    val scanSpeed = 0.25
                    val circleRadius = 1.0

                    val missionCallback = object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            ToastUtils.showToast("mission complete!")
                        }

                        override fun onFailure(error: IDJIError) {
                            ToastUtils.showToast("mission error: ${error.description()}")
                        }
                    }
                    controller.fly(missionCallback) {
                        flyBySticks(LocationUtils.RelativeDirection.FORWARD, scanDist, 0.25)
                        delay(500)

                        setCameraGimbalMode(GimbalMode.YAW_FOLLOW)
                        aimCameraAt(circleRadius, -height.value!! * .75)
                        flyCircle(circleRadius, 1.0, scanSpeed, faceCenter = true)
                        delay(500)
                        flySquare(circleRadius, scanSpeed, false)
                        delay(500)
                        resetCameraGimbal()

                        spinBy(360.0 * 2)
                        delay(500)

                        flyBySticks(LocationUtils.RelativeDirection.BACKWARD, scanDist, 0.5)
                        land()
                    }
                },

                CommandResolver.Command(
                    "ASCEND",
                    R.string.command_go_up
                ) { controller.fly { ascendBy(1.0) } },
                CommandResolver.Command(
                    "DESCEND",
                    R.string.command_go_down
                ) { controller.fly { ascendBy(-1.0) } },
                CommandResolver.Command(
                    "FORWARD",
                    R.string.command_go_forward
                ) { controller.fly { forwardBy(1.0) } },
                CommandResolver.Command(
                    "BACK UP",
                    R.string.command_go_backward
                ) { controller.fly { forwardBy(-0.5) } },
                CommandResolver.Command(
                    "LEFT",
                    R.string.command_go_left
                ) { controller.fly { leftBy(0.5) } },
                CommandResolver.Command(
                    "RIGHT",
                    R.string.command_go_right
                ) { controller.fly { leftBy(-0.5) } },

                CommandResolver.Command("STEALTH", R.string.commands_silence),
            )
        )
    }

    private fun initBtnClickListener() {
        binding?.btnEnableVirtualStick?.setOnClickListener {
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
        controller.location.observe(viewLifecycleOwner) {
            binding?.tvLocationAircraft?.text = getString(
                R.string.location_fmt_short,
                it.latitude,
                it.longitude,
                it.altitude
            )
            val updated = it
            val current = aircraftLocation
            val device = deviceLocation.value
            val dist = if (current != null && device != null) current.distanceTo(device) else 0.0
            aircraftLocation = updated
            binding?.tvDistance?.text = "${dist}m"
            binding?.tvAttitude?.text =
                "${controller.attitude.value?.toJson() ?: "-"},\nheight: ${controller.height.value}"
        }
        controller.gimbalAttitude.observe(viewLifecycleOwner) {
            binding?.tvGimbalAttitude?.text = "${it.toJson() ?: "-"}"
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
                    // ToastUtils.showToast("started takeoff.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("failed takeoff. ${error.errorCode()},${error.description()}")
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
                val bias = commandResolver.commands.flatMap {
                    it.strings(requireContext().getLocalizedResources(locale))
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
            requireContext().getLocalizedResources(locale)
        )

        when (com) {
            null -> binding?.commandResult?.text =
                requireContext().getString(R.string.error_speech_unrecognised)

            else -> {
                com.func()

                if (!silent) speakText(
                    com.strings(requireContext().getLocalizedResources(locale)).first()
                )
                binding?.commandResult?.text = com.name
            }
        }
    }

    private fun enableSimulator() {
        val initLocation = LocationCoordinate2D(
            deviceLocation.value?.latitude ?: 0.0,
            deviceLocation.value?.longitude ?: 0.0
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