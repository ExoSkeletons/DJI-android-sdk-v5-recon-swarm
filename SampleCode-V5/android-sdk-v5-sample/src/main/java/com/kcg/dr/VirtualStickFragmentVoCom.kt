package com.kcg.dr

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.kcg.dr.api.ApiServerVM
import com.kcg.dr.api.KeyActivator
import com.kcg.dr.api.actions.FlyToMe
import com.kcg.dr.api.actions.FollowMe
import com.kcg.dr.api.actions.TrackMe
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.flight.AircraftController.CircleFaceMode
import com.kcg.dr.location.LiveLocationProvider
import com.kcg.dr.location.UserVM
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import com.kcg.dr.utils.LocationUtils.bearingTo
import com.kcg.dr.utils.LocationUtils.distanceTo
import com.kcg.dr.utils.ResourcesManager
import com.kcg.dr.utils.TTSManager.speak
import com.kcg.dr.utils.as2D
import com.kcg.dr.utils.asDjiLocation
import com.kcg.dr.utils.atAlt
import com.kcg.dr.voice.CommandResolver.Command
import com.kcg.dr.voice.CommandResolver.Command.Companion.respFmtExId
import com.kcg.dr.voice.CommandResolver.Command.Companion.respFmtGoId
import com.kcg.dr.voice.CommandResolver.Command.Companion.respFmtSimpleId
import com.kcg.dr.voice.LlamaActionSequenceResolver
import com.kcg.dr.voice.RegexCommandResolver
import com.kcg.dr.voice.SpeechResolversVM
import com.kcg.dr.waypoints.LocationAdapter
import com.kcg.dr.waypoints.WaypointRepo
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVirtualStickVocomPageBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.LiveStreamVM
import dji.sampleV5.aircraft.models.RecordingVM
import dji.sampleV5.aircraft.models.SimulatorVM
import dji.sampleV5.aircraft.models.VirtualStickVM
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
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

    private var binding: FragVirtualStickVocomPageBinding? = null


    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val recordingVM: RecordingVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val liveStreamVM: LiveStreamVM by activityViewModels()
    private val controllerVM: AircraftControlVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(AircraftControlVM.STICK_VM_KEY, virtualStickVM)
            }
        },
        { AircraftControlVM.Factory }
    )
    private val apiServerVM: ApiServerVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(ApiServerVM.CONTROLLER_KEY, controllerVM.controller)
                set(ApiServerVM.USER_KEY, deviceVM.metrics)
            }
        },
        { ApiServerVM.Factory }
    )
    private val deviceVM: UserVM by activityViewModels()

    private val resolversVM: SpeechResolversVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(
                    SpeechResolversVM.RES_LIST_KEY,
                    mapOf(
                        commandResolver to SpeechResolversVM.ResolverItem(
                            R.string.commands_parser_regex,
                            R.drawable.ic_gears
                        ),
                    )
                )
            }
        },
        { SpeechResolversVM.Factory }
    )

    private val locale: Locale = ResourcesManager.locale

    private val controller: AircraftController get() = controllerVM.controller
    private lateinit var commandResolver: RegexCommandResolver
    private lateinit var actionResolver: LlamaActionSequenceResolver

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
    private var aircraftLocation: LocationCoordinate3D? = null

    // Waypoints
    private lateinit var waypointAdapter: LocationAdapter
    private lateinit var waypointRepo: WaypointRepo

    // Scenarios
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

        val maxVelocity: Double,
        val accelerationDist: Double = 2.0,
        val decelerationDist: Double = 2.0,

        val flyToTolerance: Double = 1.0,

        val followDistance: Double,
        val followVelocity: Double = maxVelocity,
        val watch12Time: Duration = 30.seconds,
        val watch6Time: Duration = 3.seconds,
        val circleError: Double = 0.0,
    )

    private val indoorsConfig = DemoFlightConfig(
        cruiseHeight = 1.5,
        followDistance = 0.0,

        flyToTolerance = 1.5,

        scanHeightHigh = 1.0,
        scanRadiusHigh = 0.4,

        scanHeightLow = 0.5,
        scanRadiusLow = 0.3,

        scanVelocity = 0.25,

        maxVelocity = 1.0,
    )
    private val denseLotConfig = DemoFlightConfig(
        cruiseHeight = 5.0,
        followDistance = 3.5,

        scanHeightHigh = 5.0,
        scanRadiusHigh = 1.5,

        scanHeightLow = 2.5,
        scanRadiusLow = 1.0,

        ascendVelocity = 1.5,
        descendVelocity = 1.0,
        scanVelocity = 1.0,
        maxVelocity = 3.0,

        circleError = -0.1,
    )
    private val emptyLotConfig = DemoFlightConfig(
        humanHeight = 3.0,
        cruiseHeight = 30.0,
        followDistance = 14.0,

        scanHeightHigh = 40.0,
        scanRadiusHigh = 12.0,

        scanHeightLow = 14.0,
        scanRadiusLow = 8.0,

        ascendVelocity = 4.0,
        descendVelocity = 2.0,
        scanVelocity = 4.0,

        maxVelocity = 8.0,
        accelerationDist = 5.0,
        decelerationDist = 15.0,

        followVelocity = 3.0,

        circleError = -0.15,
    )
    private val cfg: DemoFlightConfig = emptyLotConfig
    private var demoTextIndex = MutableLiveData(0)

    private val noAddInfo = "אין ממצאים נוספים"

    private val demoTexts = listOf(
        // תחילת תרחיש -----
        // פקודה: אחריי
        "בשעה 12, במרחק 200 מטר, הולך רגל , חולצה צהובה מתקדם לכיוונך",
        "בשעה 12 , במרחק 150 מטר, צומת דרכים.",
        // פקודה: סריקה סביבי
        // todo: פקודה: עוד ממצאים|ממצאים נוספים|ממצאים -> דיווח הבא
        "ממצאי סריקה: " +
                "בשעה 12, במרחק 100 מטר, צומת דרכים. ",
        "בשעה 1, במרחק 100 מטר שיחים, חשוד מאחורי שיחים. ",
        "בשעה 3, 250 מטר לאחר הצומת, מגרש חנייה. ",
        noAddInfo,
        // פקודה: חקור שיחים
        "ממצאי חקירה: " +
                "הולך רגל בחולצה צהובה, ללא חפצים חשודים",
        // פקודה: סריקה מגרש חנייה
        // הסייר ליד השיחים
        "ממצאי סריקה: " +
                "בשעה 3 במרחק 50 מטר, הולך רגל בחולצה אדומה. ",
        "הולך רגל בשעה 2 מהכניסה לחניה. ",
        noAddInfo,
        // פקודה: חקור חשודים
        "ממצאי חקירה: " +
                "בשעה 11, 20 מטר ממך, חשוד בחולצה אדומה עומד בקרבת הכניסה לחנייה ומתצפת. ",
        "בשעה 2, חשוד בחולצה אדומה נע לכיוון גבעת הדגל ",
        noAddInfo,
        // פקודה: אחריי
        // פקודה: סריקה מגרש חנייה
        // הסייר ליד קיר אבנים
        "ממצאי סריקה: " +
                "בשעה 3 במרחק 50 מטר, הולך רגל בחולצה אדומה. ",
        "הולך רגל בשעה 2 מהכניסה לחניה. ",
        noAddInfo,
        // פקודה: איתור שביל עוקף -> up + slow spin360
        "אותר:" +
                "בשעה 11 במרחק 10 מטרים כניסה לשביל עוקף",
        // פקודה: אחריי צדדי
        // רחפן מגיב קיבלתי ראות מוגבלת וכו'
        // רחפן טס לאט לדגל עם עיניים על הסייר, הסייר הולך לאט בשיל לדגל ומחכה ליד
        // פקודה: חקור דגל
        "ממצאי חקירה: " +
                "בשעה 2, במרחק 50 מטר, דגל אדום. ",
        "במרחק 50 מטר, שני חשודים בחולצות אדומות, סמוך לדגל. ",
        // סייר מחכה שניות ומבקש שוב ממצאים
        "שני חשודים, חולצות אדומות, תנועה לשעה 3, 150 מטר. ",
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireContext().apply {
            // API Server foreground service
            apiServerVM.startService(AircraftController.TAG)

            // Locale
            ResourcesManager.setLocale(this, Locale("he", "IL"))
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

        binding = FragVirtualStickVocomPageBinding.inflate(localInflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initVoiceCommandResolver()

        binding?.widgetHorizontalSituationIndicator?.setSimpleModeEnable(false)

        initCameraStreamSurface()
        initLiveStreamControls()
        initRecordingControls()
        recordingVM.cameraIndex.postValue(cameraIndex)

        binding?.btnMic?.setOnClickListener { resolversVM.toggleListening(locale) }

        resolversVM.isListening.observe(viewLifecycleOwner) { listening ->
            binding?.btnMic?.setImageResource(
                if (listening) R.drawable.uxsdk_ic_alert_good
                else R.drawable.ic_mic_white_36dp
            )
        }

        resolversVM.speech.observe(viewLifecycleOwner) {
            binding?.sttResult?.text = it
        }

        liveLocation.init(requireContext())
        liveLocation.locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // update device location
                    deviceVM.location.postValue(location.asDjiLocation().apply {
                        // DJI Aircraft measures alt from ground level, not sea level.
                        altitude = cfg.humanHeight
                    })

                    val fdl = String.format(
                        getString(R.string.location_fmt_short),
                        location.latitude,
                        location.longitude,
                        location.altitude
                    )
                    binding?.tvLocationDevice?.text = fdl
                }
            }
        }
        liveLocation.startRequesting()

        initWaypointControls()

        controllerVM.aircraftLocation.observe(viewLifecycleOwner) { aircraft ->
            aircraftLocation = aircraft

            val device = deviceVM.location.value

            var dist: Double? = null
            var dist2D: Double? = null
            var angleTo: Double? = null

            if (aircraft != null && device != null) {
                dist = aircraft.distanceTo(device)
                dist2D = aircraft.as2D.distanceTo(device.as2D)
                angleTo = aircraft.as2D.bearingTo(device.as2D)
                    .minus(controllerVM.heading.value ?: 0.0)
            }

            binding?.tvLocationAircraft?.text = aircraft?.let {
                String.format(
                    getString(R.string.location_fmt_short),
                    it.latitude,
                    it.longitude,
                    it.altitude
                )
            } ?: "-"
            binding?.tvDistance?.text = dist?.let { "${it}m" } ?: "-"
            binding?.tvDistance2D?.text = dist2D?.let { "${it}m" } ?: "-"
            binding?.tvAngleTo?.text = angleTo?.let { "${it.roundToInt()}°" } ?: "-"
            binding?.tvAttitude?.text = controllerVM.attitude.value?.toJson()?.toString() ?: "-"
        }
        controllerVM.aircraftHeight.observe(viewLifecycleOwner) {
            binding?.tvAircraftHeight?.text = it.toString()
        }
        controllerVM.batteryPercent.observe(viewLifecycleOwner) {
            binding?.tvBatteryPercent?.text = resources.getString(R.string.battery_percent, it)
        }
        controllerVM.attitude.observe(viewLifecycleOwner) {
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

        // tts
        resolversVM.silent.observe(viewLifecycleOwner) {
            binding?.silent?.text = "Silent : " + if (it == true) "ON" else "OFF"
        }
        // demo text speech
        demoTextIndex.observe(viewLifecycleOwner) { i ->
            when {
                i == null -> demoTextIndex.postValue(0)
                i >= demoTexts.size -> demoTextIndex.postValue(0)
                i < 0 -> demoTextIndex.postValue(demoTexts.size - 1)

                else -> binding?.tvDemoText?.text = demoTexts[i]
            }
        }
        binding?.btnDemoTextPrev?.setOnClickListener {
            demoTextIndex.postValue(demoTextIndex.value?.minus(1) ?: 0)
        }
        binding?.btnDemoTextNext?.setOnClickListener {
            demoTextIndex.postValue(demoTextIndex.value?.plus(1) ?: 0)
        }
        binding?.btnDemoTextPlay?.setOnClickListener { speakDemo() }

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
        attachOnScreenSticks(
            virtualStickVM,
            binding?.leftStickView, binding?.rightStickView
        )
        virtualStickVM.listenRCStick()
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) {
            binding?.tvControllerOwner?.text = "Control : " +
                    when (it?.state?.isVirtualStickEnable) {
                        true -> "Auto"
                        else -> "Manual"
                    }
        }
        virtualStickVM.currentSpeedLevel.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.useRcStick.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.stickValue.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.virtualStickAdvancedParam.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.text = it
        }

        apiServerVM.tunnelingUrl.observe(viewLifecycleOwner) {
            ToastUtils.showToast("tunneling url: $it")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null

        controller.destroy()
        liveLocation.stopRequesting()

        if (cameraStreamSurface != null) {
            cameraStreamManager.removeCameraStreamSurface(cameraStreamSurface!!)
            cameraStreamSurface = null
        }
        recordingVM.stopRecord() // Stop any recordings to avoid corrupting card
        if (liveStreamVM.isStreaming()) liveStreamVM.stopStream(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        apiServerVM.stopService()
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

    private fun speakDemo() {
        demoTextIndex.value?.let {
            speak(demoTexts[it], queueMode = TextToSpeech.QUEUE_FLUSH)
            demoTextIndex.postValue(it + 1)
        }
    }

    private fun initLiveStreamControls() {
        binding?.btnStartStream?.setOnClickListener {
            val factory = LayoutInflater.from(requireContext())
            val rtmpConfigView = factory.inflate(R.layout.dialog_livestream_rtmp_config_view, null)
            val etRtmpUrl = rtmpConfigView.findViewById<EditText>(R.id.et_livestream_rtmp_config)
            val configDialog = requireContext().let {
                androidx.appcompat.app.AlertDialog.Builder(
                    it,
                    R.style.Base_ThemeOverlay_AppCompat_Dialog_Alert
                )
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
            recordingVM.cameraIndex.postValue(cameraIndex)
            recordingVM.startRecord(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(p0: EmptyMsg?) =
                    ToastUtils.showToast("recording start success")

                override fun onFailure(error: IDJIError) =
                    ToastUtils.showToast("recording start fail: ${error.description()}")
            })
        }

        binding?.btnStopRecordVideo?.setOnClickListener {
            if (recordingVM.isRecording.value == true)
                recordingVM.stopRecord(object :
                    CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(p0: EmptyMsg?) =
                        ToastUtils.showToast("recording stop success")

                    override fun onFailure(error: IDJIError) =
                        ToastUtils.showToast("recording stop fail: ${error.description()}")
                })
        }

        recordingVM.isRecording.observe(viewLifecycleOwner) { v ->
            val recording = v ?: false
            binding?.tvVideoRecordingStatus?.text = "Recording: $recording"
            binding?.btnStartRecordVideo?.isEnabled = !recording
            binding?.btnStopRecordVideo?.isEnabled = recording
        }
    }

    private fun initWaypointControls() {
        // waypoint repo
        waypointRepo = WaypointRepo(requireContext())
        // saved waypoint names
        val savedWaypointNames = requireContext().getLocalizedResources(locale)
            .getStringArray(R.array.commands_mission_targets)
        // setup waypoint ui adapter
        waypointAdapter = LocationAdapter(
            viewLifecycleOwner,
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
            onLookAt = { loc ->
                controller.fly {
                    // if at location, can't look at self
                    if ((ac.location.value?.distanceTo(loc) ?: 0.0) <= cfg.flyToTolerance)
                        return@fly
                    // look at location
                    lookAtWithSpin(loc.as2D, cfg.humanHeight)
                }
            },
            deviceVM.location, controllerVM.aircraftLocation
        )
        binding?.rvWaypointLocations?.layoutManager = LinearLayoutManager(requireContext())
        binding?.rvWaypointLocations?.adapter = waypointAdapter
        lifecycleScope.launch {
            // init map with saved names as null
            for (name in savedWaypointNames)
                waypointAdapter.set(name, null)
            // load saved waypoints from repo
            waypointRepo.load()
            // insert saved waypoints into ui adapter
            for ((name, location) in waypointRepo.locations())
                waypointAdapter.set(name, location)
            // update repo on waypoint change
            waypointAdapter.onLocationChanged = { name, location ->
                lifecycleScope.launch { waypointRepo.put(name, location) }
            }
        }
    }

    private fun initCameraStreamSurface() {
        svCameraStream = binding?.svCameraStream ?: return
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
        stickVM: VirtualStickVM,
        leftStk: OnScreenJoystick?,
        rightStk: OnScreenJoystick?,
        deviation: Double = 0.02,
    ) {
        leftStk?.setJoystickListener(object : OnScreenJoystickListener {
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
        rightStk?.setJoystickListener(object : OnScreenJoystickListener {
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
    }

    private fun initVoiceCommandResolver() {
        commandResolver = RegexCommandResolver(requireContext())
        commandResolver.setCommands(
            listOf(
                Command(R.string.commands_stop) { controller.stop() },
                Command(R.string.command_takeoff, respFmtSimpleId) {
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
                Command(R.string.command_land, respFmtExId) { controller.fly { land() } },

                Command(
                    R.string.commands_return_home,
                    respFmtExId
                ) { toMe() },
                Command(
                    R.string.command_follow_target,
                    respFmtExId
                ) { track() },

                Command(
                    R.string.command_follow_me,
                    respFmtExId,
                    R.string.commands_mission_follow_me_name
                ) { followMe() },
                Command(
                    R.string.commands_follow_me_side,
                    respFmtExId,
                    R.string.commands_mission_follow_me_side_name
                ) {
                    // faked for demo >_>
                    val flagLocationIndex = 3
                    val targetNames = requireContext().getLocalizedResources(locale)
                        .getStringArray(R.array.commands_mission_targets)
                    val flagKey = targetNames[flagLocationIndex]
                    val targetLocation = waypointRepo.locations()[flagKey]
                        ?: throw RuntimeException("can't find flag location")
                    controller.fly {
                        withEyesOn(deviceVM.location) {
                            flyToSticks(targetLocation, maxVelocity = cfg.followVelocity * .5)
                        }
                    }
                },
                Command(
                    R.string.commands_scan_paths,
                    respFmtExId
                ) {
                    controller.fly {
                        pitchCamera(-90.0)
                        ascendBy(3.0, velocity = 2.0)
                        scanGround(1.0, cfg.scanVelocity, CircleFaceMode.OUTWARDS)
                        speakDemo()
                    }
                },
                Command(
                    R.string.command_mission_recon,
                    respFmtExId,
                    R.string.commands_mission_recon_name
                ) { match ->
                    val selfReconLocation = deviceVM.location.value?.atAlt(cfg.scanHeightHigh)
                    val (nameKey, target) = matchWaypointLocationFromRegexCapture(
                        match,
                        selfReconLocation
                    )
                    ToastUtils.showToast("recon-ning${target?.let { " " + if (it == selfReconLocation) "you" else "$nameKey:\n$it" } ?: ""}")

                    controller.fly {
                        takeoff()

                        pitchCamera(-90.0)

                        if (target != null) {
                            // fly first to location for recon
                            flyToSticks(
                                target,
                                maxVelocity = cfg.maxVelocity,
                                accelerationDist = cfg.accelerationDist,
                                decelerationDist = cfg.decelerationDist
                            )
                        }
                        delay(1.seconds)

                        val startHeight = ac.height.value
                        val reconHeight = (startHeight - 15.0)
                            // go no lower than human height (don't hit ground)
                            .coerceAtLeast(cfg.humanHeight)
                            // go no lower than lowest scan height
                            .coerceAtLeast(cfg.scanHeightLow)
                            // go no higher than start point (recon shouldn't go up)
                            .coerceAtMost(startHeight)
                        ascendTo(reconHeight, cfg.descendVelocity)
                        delay(1.seconds)

                        pitchCamera(-60.0)
                        spinBy(360.0)

                        speakDemo()

                        delay(1.seconds)
                        pitchCamera(-90.0)
                        ascendTo(startHeight, cfg.ascendVelocity)
                    }
                },
                Command(
                    R.string.command_mission_scan,
                    respFmtExId,
                    R.string.commands_mission_scan_name
                ) { match ->
                    val selfScanLocation = deviceVM.location.value?.atAlt(cfg.scanHeightHigh)
                    val (nameKey, target) = matchWaypointLocationFromRegexCapture(
                        match,
                        selfScanLocation
                    )
                    val scanFaceMode =
                        if (target == null || target == selfScanLocation) CircleFaceMode.OUTWARDS
                        else CircleFaceMode.INWARDS
                    ToastUtils.showToast("scanning${target?.let { " " + if (it == selfScanLocation) "you" else "$nameKey:\n$it" } ?: ""}")

                    controller.fly {
                        takeoff()

                        if (target != null) {
                            // fly to location
                            withEyesOn(
                                MutableLiveData(target.atAlt(cfg.humanHeight))
                            ) {
                                // fly to location
                                flyToSticks(
                                    target,
                                    maxVelocity = cfg.maxVelocity,
                                    accelerationDist = cfg.accelerationDist,
                                    decelerationDist = cfg.decelerationDist
                                )
                            }
                        } else {
                            // generic area scan
                            pitchCamera(-90.0)
                            val startHeight = ac.height.value
                            ascendTo(
                                cfg.scanHeightHigh.coerceAtLeast(startHeight),
                                cfg.ascendVelocity
                            )
                        }
                        delay(1.seconds)
                        scanGround(cfg.scanRadiusHigh, cfg.scanVelocity, scanFaceMode)

                        speakDemo()
                    }
                },
                Command(R.string.commands_more_info) { speakDemo() },

                Command(
                    R.string.command_hello,
                    respFmtSimpleId,
                ) { controller.fly { wave() } },
                Command(
                    R.string.command_circle,
                    respFmtExId,
                ) {
                    controller.fly {
                        val r = 1.0
                        val v = 1.0
                        ToastUtils.showToast("circle: fromCenter, center, clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.INWARDS,
                            clockwise = true,
                            fromCenter = true
                        )
                        ToastUtils.showToast("circle: fromCenter, center, x-clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.INWARDS,
                            clockwise = false,
                            fromCenter = true
                        )
                        ToastUtils.showToast("circle: fromCenter, outer, clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.OUTWARDS,
                            clockwise = true,
                            fromCenter = true
                        )
                        ToastUtils.showToast("circle: fromCenter, outer, x-clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.OUTWARDS,
                            clockwise = false,
                            fromCenter = true
                        )
                        delay(2.seconds)
                        ToastUtils.showToast("circle: center, clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.INWARDS,
                            clockwise = true,
                            fromCenter = false
                        )
                        ToastUtils.showToast("circle: center, x-clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.INWARDS,
                            clockwise = false,
                            fromCenter = false
                        )
                        ToastUtils.showToast("circle: outer, clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.OUTWARDS,
                            clockwise = true,
                            fromCenter = false
                        )
                        ToastUtils.showToast("circle: outer, x-clockwise")
                        flyCircle(
                            r,
                            v,
                            faceMode = CircleFaceMode.OUTWARDS,
                            clockwise = false,
                            fromCenter = false
                        )
                    }
                },
                Command(
                    R.string.command_square, respFmtExId,
                ) { controller.fly { flySquare(5.0, velocity = 2.5) } },
                Command(
                    R.string.command_cam_fan,
                    respFmtSimpleId,
                ) { controller.fly { gimbalFan() } },
                Command(
                    R.string.command_spin, respFmtSimpleId,
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
                ) { resolversVM.silent.postValue(resolversVM.silent.value != true) },
            )
        )

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                actionResolver = LlamaActionSequenceResolver(
                    requireContext(),
                    "qwen2.5-coder-1.5b-instruct-q4_0.gguf",
                    listOf("he"),
                    controller,
                    deviceVM.metrics
                )
                actionResolver.init()
                /*    val actions = actionResolver.resolve(
                        """
                        hey drone. take off and fly up 10 meters, spin around slowly, wait a few seconds
                        then come down halfway, wait another second then come down and land. over and out
                    """.trimIndent()
                    )
                    Log.i("LlamaActionResolver", "actions: $actions")
               */
            } catch (e: Exception) {
                Log.e("LlamaActionResolver", "error: ${e.message}", e)
            }
        }
    }

    private fun matchWaypointLocationFromRegexCapture(
        regexMatch: MatchResult,
        selfLocation: LocationCoordinate3D?
    ): Pair<String?, LocationCoordinate3D?> {
        // extract the args target from the regex match capture group
        val args = regexMatch.groups[1]?.value ?: ""

        val waypoints = waypointRepo.locations()

        val waypointAliases = requireContext().getLocalizedResources(locale)
            .getStringArray(R.array.commands_mission_targets).toMutableList()
        val deviceAliases = requireContext().getLocalizedResources(locale)
            .getString(R.string.commands_mission_target_device)

        // try match args to a waypoint target
        var nameKey: String? = null
        val target = when {
            args.isBlank() -> null // no target specified in command args, perform generic scan
            else -> when {
                // args match self alias
                deviceAliases.toRegex().containsMatchIn(args) ->
                    // choose device location as scan target
                    selfLocation ?: throw RuntimeException("device location unavailable")

                else -> {
                    // try match args to the list of waypoint targets aliases
                    waypointAliases.forEachIndexed { i, aliases ->
                        Log.i(
                            "LocationResolver",
                            "matching aliases $i) $aliases to args $args:"
                        )
                        if (aliases.toRegex().containsMatchIn(args)) {
                            nameKey = aliases
                            Log.i("LocationResolver", "matched. index=$i")
                            return@forEachIndexed
                        }
                    }
                    if (nameKey == null) {
                        Log.d("LocationResolver", "no key match for arg: $args")
                        throw RuntimeException("no such location: $args")
                    }

                    // return the location of the matched waypoint target
                    waypoints[nameKey] ?: throw RuntimeException("no location set for $args")
                }
            }
        }
        return Pair(nameKey, target)
    }

    private fun enableSimulator() {
        val initLocation = LocationCoordinate2D(
            deviceVM.location.value?.latitude ?: 0.0,
            deviceVM.location.value?.longitude ?: 0.0
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

    private fun followMe() = controller.fly {
        FollowMe(
            cfg.cruiseHeight,
            cfg.followDistance,
            cfg.followVelocity,
            cfg.accelerationDist,
            cfg.decelerationDist
        ).act(this, deviceVM.metrics)
    }

    private fun toMe() = controller.fly {
        FlyToMe(
            cfg.maxVelocity,
            cfg.accelerationDist,
            cfg.decelerationDist
        ).act(this, deviceVM.metrics)
    }

    private fun track() = controller.fly {
        TrackMe().act(this, deviceVM.metrics)
    }
}