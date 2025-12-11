package com.kcg.dr.vocom

import LocationAdapter
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.kcg.dr.LiveLocationProvider
import com.kcg.dr.LocaleUtils.getLocalizedResources
import com.kcg.dr.LocationUtils
import com.kcg.dr.LocationUtils.distanceTo
import com.kcg.dr.LocationUtils.translate
import com.kcg.dr.SFXManager
import com.kcg.dr.api.ApiServerService
import com.kcg.dr.api.KeyActivator
import com.kcg.dr.as2D
import com.kcg.dr.as3D
import com.kcg.dr.controller.AircraftController
import com.kcg.dr.controller.AircraftController.CircleFaceMode
import com.kcg.dr.vocom.CommandResolver.Command
import com.kcg.dr.waypoint.WPLocationRepository
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVirtualStickVocomPageBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.CameraActionVM
import dji.sampleV5.aircraft.models.CameraGimbalVM
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
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VirtualStickFragmentVoCom : DJIFragment() {
    // TODO: this fragment is a patchwork nightmare of several smaller fragments,
    //  which aught to be split right up to separate tts,vo-com,location,recognition-tcp fragments
    //  run in our own activity (and not this wacko feature-demo one) with Navigation UI -
    //  if only we can figure out how to setup properly a fragment that still retains
    //  a reference to the DJI activity needed to get all the view models.
    //  *
    //  God willing we may even figure out how to ditch view models entirely and go pure SDK... for now the app setup
    //  (permissions, vm's, activities...) is just too nice to give up.


    private val locale = Locale("iw", "IL")

    private val intelligentFlightVM: IntelligentFlightVM by activityViewModels()
    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val cameraGimbalVM: CameraGimbalVM by activityViewModels()
    private val cameraVM: CameraActionVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val wayPointV3VM: WayPointV3VM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val liveStreamVM: LiveStreamVM by activityViewModels()

    private lateinit var controller: AircraftController
    private val commandResolver: CommandResolver = CommandResolver()

    private var binding: FragVirtualStickVocomPageBinding? = null

    private val deviation: Double = 0.02

    // Live camera feed
    private lateinit var svCameraStream: SurfaceView
    private var cameraStreamSurface: Surface? = null
    private var cameraStreamWidth: Int = -1
    private var cameraStreamHeight: Int = -1
    private var cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    private var cameraStreamScaleType: ICameraStreamManager.ScaleType =
        ICameraStreamManager.ScaleType.CENTER_INSIDE
    private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager

    // Location
    private val liveLocation: LiveLocationProvider = LiveLocationProvider(
        this,
        200, 50,
        500,
        Priority.PRIORITY_HIGH_ACCURACY
    )
    private val deviceLocation: MutableLiveData<LocationCoordinate3D> = MutableLiveData()
    private var aircraftLocation: LocationCoordinate3D? = null

    // Waypoints
    private lateinit var waypointAdapter: LocationAdapter
    private lateinit var waypointRepo: WPLocationRepository

    class DemoFlightConfig(
        val humanHeight: Double = 2.0,
        val cruiseHeight: Double,

        val scanHeightHigh: Double,
        val scanRadiusHigh: Double,

        val scanHeightLow: Double,
        val scanRadiusLow: Double,

        val ascendVelocity: Double = 0.5,
        val descendVelocity: Double = 0.5,
        val scanVelocity: Double,

        val maxVelocity: Double = 1.0,
        val accelerationDist: Double = 2.0,
        val decelerationDist: Double = 2.0,

        val flyToTolerance: Double = 0.0,

        val followDistance: Double,
        val watch12Time: Duration = 25.seconds,
        val watch6Time: Duration = 10.seconds,
        val circleError: Double = 0.0,
    )

    private val indoorsConfig = DemoFlightConfig(
        cruiseHeight = 1.5,
        followDistance = 0.0,

        scanHeightHigh = 1.0,
        scanRadiusHigh = 0.4,

        scanHeightLow = 0.5,
        scanRadiusLow = 0.3,

        scanVelocity = 0.25,
    )
    private val denseLotConfig = DemoFlightConfig(
        cruiseHeight = 5.0,
        followDistance = 3.5,

        scanHeightHigh = 5.0,
        scanRadiusHigh = 1.5,

        scanHeightLow = 2.5,
        scanRadiusLow = 1.0,

        scanVelocity = 1.0,

        circleError = -0.1,
    )
    private val emptyLotConfig = DemoFlightConfig(
        humanHeight = 3.0,
        cruiseHeight = 20.0,
        followDistance = 12.0,

        scanHeightHigh = 30.0,
        scanRadiusHigh = 10.0,

        scanHeightLow = 10.0,
        scanRadiusLow = 8.0,

        ascendVelocity = 4.0,
        descendVelocity = 2.5,
        scanVelocity = 4.0,

        maxVelocity = 8.0,
        accelerationDist = 5.0,
        decelerationDist = 15.0,

        circleError = -0.15,
    )
    private val cfg: DemoFlightConfig = emptyLotConfig


    // Speech recognition launcher
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
    private var silent = MutableLiveData(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireContext().apply {
            // Server foreground service
            ApiServerService.start(this, 8080)
            // TTS
            tts = TextToSpeech(this, onInitListener)
        }
        SFXManager.init(context = requireContext())
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

        binding = FragVirtualStickVocomPageBinding.inflate(localInflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initController()
        initVoiceCommandResolver()

        binding?.widgetHorizontalSituationIndicator?.setSimpleModeEnable(false)

        svCameraStream = view.findViewById(R.id.sv_camera_stream)
        initCameraStreamSurfaceCallback()
        initLiveStreamControls()
        initRecordingControls()
        cameraVM.setCameraIndex(cameraIndex)

        binding?.btnMic?.setOnClickListener { startListening() }

        liveLocation.init(requireContext())
        liveLocation.locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // update device location
                    deviceLocation.postValue(LocationCoordinate3D().apply {
                        latitude = location.latitude
                        longitude = location.longitude

                        // DJI Aircraft measures alt from ground level, not sea level.
                        altitude = cfg.humanHeight
                    })

                    val fdl = getString(
                        R.string.location_fmt_short,
                        location.latitude,
                        location.longitude,
                        location.altitude
                    )
                    binding?.tvLocationDevice?.text = fdl
                }
            }
        }
        liveLocation.startRequesting()

        waypointRepo = WPLocationRepository(requireContext())
        val waypointLocations = waypointRepo.locations.toMutableList()
        val waypointNames = requireContext().getLocalizedResources(locale)
            .getStringArray(R.array.commands_mission_targets)
            .toMutableList()
        waypointAdapter = LocationAdapter(
            waypointLocations,
            waypointNames,
            onFlyTo = { loc ->
                controller.fly {
                    flyToSticks(
                        loc,
                        maxVelocity = cfg.maxVelocity,
                        accelerationDist = cfg.accelerationDist,
                        decelerationDist = cfg.decelerationDist
                    )
                }
            },
            onDelete = { loc ->
                lifecycleScope.launch {
                    waypointRepo.remove(loc) // suspend safe
                    waypointAdapter.remove(loc) // update UI
                }
            }
        )
        binding?.rvWaypointLocations?.layoutManager = LinearLayoutManager(requireContext())
        binding?.rvWaypointLocations?.adapter = waypointAdapter
        lifecycleScope.launch {
            waypointRepo.load()
            waypointAdapter.set(waypointRepo.locations)
        }
        controller.location.observe(viewLifecycleOwner) {
            binding?.btnWaypointAddAircraft?.isEnabled = it != null
        }
        deviceLocation.observe(viewLifecycleOwner) {
            binding?.btnWaypointAddAircraft?.isEnabled = it != null
        }
        binding?.btnWaypointAddAircraft?.setOnClickListener {
            controller.location.value?.let {
                lifecycleScope.launch {
                    waypointRepo.add(it)
                    waypointAdapter.add(it)
                }
            } ?: ToastUtils.showToast("aircraft location unavailable")
        }
        binding?.btnWaypointAddDevice?.setOnClickListener {
            deviceLocation.value?.let {
                Log.d("DeviceLocation", "add aircraft")
                lifecycleScope.launch {
                    waypointRepo.add(it)
                    waypointAdapter.add(it)
                }
            } ?: ToastUtils.showToast("device location unavailable")
        }

        controller.location.observe(viewLifecycleOwner) {
            binding?.tvLocationAircraft?.text =
                if (it != null) getString(
                    R.string.location_fmt_short,
                    it.latitude,
                    it.longitude,
                    it.altitude
                ) else ""
            aircraftLocation = it
            val aircraft = aircraftLocation
            val device = deviceLocation.value
            var dist = 0.0
            var dist2D = 0.0
            if (aircraft != null && device != null) {
                dist = aircraft.distanceTo(device)
                dist2D = aircraft.as2D.distanceTo(device.as2D)
            }
            binding?.tvDistance?.text = "${dist}m"
            binding?.tvDistance2D?.text = "${dist2D}m"
            binding?.tvAttitude?.text = "${controller.attitude.value?.toJson() ?: "-"},\n" +
                    "height: ${controller.height.value}"
        }
        controller.height.observe(viewLifecycleOwner) {
            binding?.tvAircraftHeight?.text = it.toString()
        }
        controller.batteryPercent.observe(viewLifecycleOwner) {
            binding?.tvBatteryPercent?.text = resources.getString(R.string.battery_percent, it)
        }
        controller.gimbalAttitude.observe(viewLifecycleOwner) {
            binding?.tvGimbalAttitude?.text = "${it?.toJson() ?: "-"}"
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

        binding?.btnStop?.setOnClickListener { controller.stop() }
        binding?.btnFollow?.setOnClickListener { followMe() }
        binding?.btnToMe?.setOnClickListener { toMe() }
        binding?.btnFollowCam?.setOnClickListener { track() }

        silent.observe(viewLifecycleOwner) {
            binding?.silent?.text = "Silent : " + if (it == true) "ON" else "OFF"
        }

        binding?.btnDisableSim?.setOnClickListener { disableSimulator(null) }
        binding?.btnEnableSim?.setOnClickListener { enableSimulator() }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.apply {
                text = it
                setTextColor(if (simulatorVM.isSimulatorOn()) Color.WHITE else Color.RED)
            }
        }

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

        tts.stop()
        tts.shutdown()

        controller.destroy()
        liveLocation.stopRequesting()
        if (cameraStreamSurface != null) {
            cameraStreamManager.removeCameraStreamSurface(cameraStreamSurface!!)
            cameraStreamSurface = null
        }
        binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        val intent = Intent(requireContext(), ApiServerService::class.java)
        requireContext().stopService(intent) // Stop API service
        cameraVM.stopRecord() // Stop any recordings to avoid corrupting card
        SFXManager.release()
    }

    override fun onResume() {
        super.onResume()
        liveLocation.startRequesting() // re-enable location requesting if necessary

        // restart TTS to recheck available voices
        //  tts.shutdown()
        //  tts = TextToSpeech(requireContext(), onInitListener)
    }

    override fun onPause() {
        super.onPause()
        liveLocation.stopRequesting() // disable location requesting to conserve battery
    }

    private fun speakText(text: String, locale: Locale? = this.locale) {
        if (text.isNotBlank() && !(silent.value ?: false)) {
            if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                promptInstallTTSLang()
                return
            }
            tts.language = locale
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

    private fun initRecordingControls() {
        binding?.btnStartRecordVideo?.setOnClickListener {
            cameraVM.setCameraIndex(cameraIndex)
            cameraVM.startRecord(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(p0: EmptyMsg?) =
                    ToastUtils.showToast("recording start success")

                override fun onFailure(error: IDJIError) =
                    ToastUtils.showToast("recording start fail: ${error.description()}")
            })
        }

        binding?.btnStopRecordVideo?.setOnClickListener {
            if (cameraVM.isRecording.value == true)
                cameraVM.stopRecord(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(p0: EmptyMsg?) =
                        ToastUtils.showToast("recording stop success")

                    override fun onFailure(error: IDJIError) =
                        ToastUtils.showToast("recording stop fail: ${error.description()}")
                })
        }

        cameraVM.isRecording.observe(viewLifecycleOwner) { v ->
            val recording = v ?: false
            binding?.tvVideoRecordingStatus?.text = "Recording: $recording"
            binding?.btnStartRecordVideo?.isEnabled = !recording
            binding?.btnStopRecordVideo?.isEnabled = recording
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
            cameraGimbalVM,
            intelligentFlightVM,
            wayPointV3VM
        )
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) {
            binding?.tvControllerOwner?.text = "Control : " +
                    when (it?.state?.isVirtualStickEnable) {
                        true -> "Auto"
                        else -> "Manual"
                    }
        }
        virtualStickVM.stickValue.observe(viewLifecycleOwner) {
            if (controller.isVirtualStickEnabled()) {
                Log.w("RCSticks", "RC Sticks touched when virtual stick had control")

                ToastUtils.showShortToast("Controller Override")
                speakText("Controller Override", Locale.ENGLISH)
                controller.stop() // stop controller return control to manual RC
                // FIXME: this seems to trigger a disable virtual stick attempt twice,
                //  which gets registered as a failure to disable the second time
            }
        }
        controller.init()
        controller.takeStickControl(object : CommonCallbacks.CompletionCallback {
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
        val demoScanSusInfo = "זוהו מספר חשודים מלפנים."
        val demoPrefix = "ממצאי חקירה:"
        val demoScanInfo = listOf(
            "אין ממצאים.",
            "זיהיתי רכב אחד בתנועה מלפנים, וזיהיתי שתי אנשים מלפנים במרחק 15 מטר.",
            "זיהיתי אדם חמוש מתחתיי ואדם מאחורי רכב.",
        )

        val respFmtExId = R.string.commands_response_fmt_executing
        val respFmtGoId = R.string.commands_response_fmt_going

        commandResolver.commands.clear()
        commandResolver.commands.addAll(
            arrayOf(
                Command(R.string.commands_stop) { controller.stop() },
                Command(R.string.commands_takeoff) {
                    controller.fly {
                        try {
                            val response = KeyActivator.handleKeyRequest(
                                Json.Default.decodeFromString(
                                    "{\"group\":\"flight_controller\", \"key\":\"StartTakeoff\"}"
                                )
                            )
                            Log.d(
                                "KeyExecutor",
                                "OK: $response"
                            )
                        } catch (e: Exception) {
                            ToastUtils.showToast("error: ${e.message}")
                            Log.e("KeyExecutor", "${e.message}")
                        }
                        //takeoff()
                    }
                },
                Command(R.string.commands_land) { controller.fly { land() } },

                Command(
                    R.string.commands_return_home,
                    respFmtExId
                ) { toMe() },
                Command(
                    R.string.commands_follow_target,
                    respFmtExId
                ) { track() },

                Command(
                    R.string.commands_follow_me,
                    respFmtExId
                ) { followMe() },
                Command(
                    R.string.commands_mission_recon,
                    respFmtExId,
                    R.string.commands_mission_recon_name
                ) {
                    controller.fly {
                        val startHeight = height.value!!
                        ascendTo(cfg.scanHeightLow, cfg.descendVelocity)
                        delay(1.seconds)
                        scanGround(cfg.scanRadiusLow, cfg.scanVelocity)
                        speakText(demoPrefix + " " + demoScanInfo.random())
                        delay(1.seconds)
                        ascendTo(startHeight, cfg.ascendVelocity)
                    }
                },
                Command(
                    R.string.commands_mission_scan,
                    R.string.commands_response_fmt_executing,
                    R.string.commands_mission_scan_name
                ) { match ->
                    // extract the scan target from the spoken text
                    val args = match.groups[1]?.value ?: ""

                    val waypoints = waypointRepo.locations
                    val dl = deviceLocation.value?.as2D?.as3D(cfg.scanHeightHigh)

                    val waypointAliases = requireContext().getLocalizedResources(locale)
                        .getStringArray(R.array.commands_mission_targets).toMutableList()
                    val deviceAliases = requireContext().getLocalizedResources(locale)
                        .getString(R.string.commands_mission_target_device)

                    var index: Int = -1
                    val target = when {
                        args.isBlank() -> null
                        else -> when {
                            deviceAliases.toRegex().containsMatchIn(args) ->
                                dl ?: throw RuntimeException("device location unavailable")

                            else -> {
                                waypointAliases.forEachIndexed { i, aliases ->
                                    if (i >= waypoints.size) return@forEachIndexed

                                    Log.i(
                                        "LocationResolver",
                                        "matching aliases $aliases to args $args:"
                                    )
                                    if (aliases.toRegex().containsMatchIn(args)) {
                                        index = i
                                        Log.i("LocationResolver", "matched. index=$index")
                                        return@forEachIndexed
                                    }
                                    Log.i("LocationResolver", "index $index")
                                }
                                if (index < 0) {
                                    speakText("no such target:\n $args")
                                    throw RuntimeException("no match for $args")
                                }
                                waypoints[index]
                            }
                        }
                    }

                    ToastUtils.showToast("scanning${target?.let { " " + if (it == dl) "you" else "$index:$it" } ?: ""}")

                    return@Command

                    controller.fly {
                        takeoff()
                        target?.let {
                            flyToSticks(
                                target,
                                maxVelocity = cfg.maxVelocity,
                                accelerationDist = cfg.accelerationDist,
                                decelerationDist = cfg.decelerationDist
                            )
                        } ?: ascendTo(cfg.scanHeightHigh, cfg.ascendVelocity)
                        delay(1.seconds)
                        scanGround(
                            cfg.scanRadiusHigh,
                            cfg.scanVelocity,
                            faceMode = CircleFaceMode.OUTER
                        )
                        speakText(demoPrefix + " " + demoScanInfo.random())
                    }
                },

                Command(
                    R.string.command_hello
                ) { controller.fly { wave() } },
                Command(
                    R.string.commands_circle
                ) { controller.fly { flyCircle(1.0, velocity = 0.5) } },
                Command(
                    R.string.commands_square
                ) { controller.fly { flySquare(5.0, velocity = 2.5) } },
                Command(
                    R.string.commands_cam_fan
                ) { controller.fly { gimbalFan() } },
                Command(
                    R.string.commands_spin
                ) { controller.fly { spinBy(360.0, velocity = 50.0) } },

                Command(
                    R.string.command_go_up,
                    respFmtGoId
                ) { controller.fly { ascendBy(1.0) } },
                Command(
                    R.string.command_go_down,
                    respFmtGoId
                ) { controller.fly { ascendBy(-1.0) } },
                Command(
                    R.string.command_go_forward,
                    respFmtGoId
                ) { controller.fly { forwardBy(1.0) } },
                Command(
                    R.string.command_go_backward,
                    respFmtGoId
                ) { controller.fly { forwardBy(-0.5) } },
                Command(
                    R.string.command_go_left,
                    respFmtGoId
                ) { controller.fly { leftBy(0.5) } },
                Command(
                    R.string.command_go_right,
                    respFmtGoId
                ) { controller.fly { leftBy(-0.5) } },

                Command(
                    R.string.commands_silence
                ) { silent.postValue(silent.value != true) },
            )
        )
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
        }

        try {
            speechRecognizerLauncher.launch(intent)
        } catch (_: Exception) {
            binding?.txtSpeechResult?.text =
                getString(R.string.dji_msdk_error_common_unsupported)
        }
    }

    private fun onHearText(spokenText: String) {
        val res = commandResolver.resolve(
            spokenText,
            requireContext().getLocalizedResources(locale)
        )

        when (res) {
            null -> binding?.commandResult?.text =
                requireContext().getString(R.string.error_speech_unrecognised)

            else -> {
                val (com, match) = res
                try {
                    com.func(match)
                    SFXManager.playSfx(SFXManager.SFX.ACTION_CONFIRM)
                    val response =
                        requireContext().getLocalizedResources(locale)
                            .getString(R.string.commands_response_fmt_accepted) + ". " +
                                com.response(requireContext().getLocalizedResources(locale))
                    speakText(response)
                    binding?.commandResult?.text =
                        com.name(requireContext().getLocalizedResources(locale))
                } catch (e: Exception) {
                    SFXManager.playSfx(SFXManager.SFX.NOTIFY_TECHNICAL)
                    ToastUtils.showToast(e.message ?: e.toString())
                }
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

    private suspend fun awaitDeviceLocation(
        timeout: Duration = Duration.INFINITE,
        updateInterval: Duration = 100.milliseconds
    ) = coroutineScope {
        require(timeout >= updateInterval) { "timeout $timeout to short, must be greater than update interval $updateInterval" }

        Log.d("DeviceLocation", "awaiting location")
        liveLocation.startRequesting()
        val res = withTimeoutOrNull(timeout) {
            while (isActive && deviceLocation.value == null)
                delay(updateInterval)
        }
        if (res == null) {
            Log.d("DeviceLocation", "timed out waiting for location")
            throw IllegalStateException("device location not available")
        }
        Log.d("DeviceLocation", "got location")
    }


    private fun followMe() = controller.fly {
        scope.launch {
            awaitDeviceLocation()
            takeoff()
        }

        // If aircraft is far from a perch position, move closer
        val dl = deviceLocation.value!!
        val pl = dl.apply { altitude = cfg.cruiseHeight }
        if (abs(
                location.value!!.as2D.distanceTo(dl.as2D)
                        - cfg.followDistance
            ) > cfg.flyToTolerance
        ) {
            ToastUtils.showToast("device far away. looking for you")
            lookAtWithSpin(dl.as2D, dl.altitude)
            ToastUtils.showToast("moving to perch")
            flyToSticks(
                pl.translate(
                    cfg.followDistance,
                    LocationUtils.RelativeDirection.BACKWARD,
                    heading.value!!
                ),
                maxVelocity = cfg.maxVelocity,
                accelerationDist = cfg.accelerationDist,
                decelerationDist = cfg.decelerationDist,
            )
        }

        // Orbiting pattern
        while (currentCoroutineContext().isActive) {
            ToastUtils.showToast("following you")
            withTimeoutOrNull(cfg.watch12Time) {
                perchShoulder(
                    deviceLocation,
                    cfg.cruiseHeight, cfg.followDistance,
                    faceTarget = true
                )
                // trailShoulder(deviceLocation, cfg.preferredAlt, cfg.perchDistance)
            }
            brakeFor(100.milliseconds)

            ToastUtils.showToast("watching 6")
            spinBy(180.0, velocity = 140.0)
            delay(cfg.watch6Time)
            spinBy(180.0, velocity = 140.0)
            /*withTimeoutOrNull(cfg.frontTime) {
                perchShoulder(
                    deviceLocation,
                    cfg.preferredAlt, cfg.followDistance,
                    faceTarget = false
                )
            }*/
        }
    }

    private fun toMe() = controller.fly {
        ToastUtils.showToast("following phone location")
        scope.launch {
            awaitDeviceLocation()
            takeoff()
        }
        flyToSticks(
            deviceLocation.value!!,
            maxVelocity = cfg.maxVelocity,
            accelerationDist = cfg.accelerationDist,
            decelerationDist = cfg.decelerationDist
        )
    }

    private fun track() = controller.fly {
        ToastUtils.showToast("camera tracking phone location")
        scope.launch {
            awaitDeviceLocation()
            takeoff()
        }
        lookAtAndTrack(deviceLocation)
    }
}