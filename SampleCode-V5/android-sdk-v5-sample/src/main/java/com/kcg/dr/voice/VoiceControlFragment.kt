package com.kcg.dr.voice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kcg.dr.api.dto.actions.FlyToMe
import com.kcg.dr.api.dto.actions.FollowMe
import com.kcg.dr.api.dto.actions.ScanGround
import com.kcg.dr.api.dto.actions.TrackMe
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.location.UserVM
import com.kcg.dr.managers.SFXManager
import com.kcg.dr.managers.SFXManager.SFX
import com.kcg.dr.managers.TTSManager.speak
import com.kcg.dr.utils.LocaleUtils
import com.kcg.dr.utils.getLocalIpAddress
import com.kcg.dr.voice.CommandResolver.Command
import com.kcg.dr.voice.CommandResolver.Command.Companion.respFmtExId
import com.kcg.dr.voice.CommandResolver.Command.Companion.respFmtSimpleId
import com.kcg.dr.voice.SpeechResolversVM.ResolverViewState
import com.stealthcopter.networktools.SubnetDevices
import com.stealthcopter.networktools.subnet.Device
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomVoiceControlBinding
import dji.sampleV5.aircraft.databinding.ItemResolverBinding
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceControlFragment : Fragment() {
    private var _binding: FragVocomVoiceControlBinding? = null
    private val binding get() = _binding!!

    private lateinit var commandResolver: RegexCommandResolver
    private lateinit var actionResolver: LlamaActionSequenceResolver
    private val groundStationResolver = GroundStationSpeechResolver()

    private val controllerVM: AircraftControlVM by activityViewModels()
    private val userVM: UserVM by activityViewModels()
    private val viewModel: SpeechResolversVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(
                    SpeechResolversVM.RES_LIST_KEY,
                    mapOf(
                        commandResolver to SpeechResolversVM.ResolverItem(
                            R.string.commands_parser_regex,
                            R.drawable.ic_gears
                        ),
                        groundStationResolver to SpeechResolversVM.ResolverItem(
                            R.string.commands_parser_gs,
                            R.drawable.ic_llm_brain
                        ),
                        /*actionResolver to SpeechResolversVM.ResolverItem(
                            R.string.commands_parser_llm,
                            R.drawable.ic_llm_brain
                        )*/
                    )
                )
            }
        },
        { SpeechResolversVM.Factory }
    )
    private val locale get() = LocaleUtils.preferred

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomVoiceControlBinding.inflate(inflater, container, false)
        // ResourcesManager.setLocale(requireContext(), Locale("he", "IL"))
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val controller = controllerVM.controller
        commandResolver = RegexCommandResolver(requireContext())
        commandResolver.setCommands(
            listOf(
                Command(R.string.commands_stop) { controller.stop() },
                Command(R.string.command_takeoff, respFmtSimpleId) { controller.fly { takeoff() } },
                Command(R.string.command_land, respFmtExId) { controller.fly { land() } },
                Command(
                    R.string.command_spin,
                    respFmtSimpleId
                ) { controller.fly { spinBy(360.0, velocity = 120.0) } },

                Command(R.string.command_mission_scan, respFmtExId) {
                    controller.fly { ScanGround(velocity = 2.0).act(this, userVM.metrics) }
                },
                Command(R.string.command_mission_recon, respFmtExId) {
                    controller.fly {
                        val h0 = controller.ac.height.value
                        ascendTo(4.5, velocity = 1.0)
                        scanGround(2.0, 1.0)
                        ascendTo(h0)
                    }
                },
                Command(R.string.command_hello, respFmtSimpleId) { controller.fly { wave() } },

                Command(R.string.commands_silence) { viewModel.silent.postValue(viewModel.silent.value != true) },
                Command(R.string.commands_info_battery) {
                    requireContext().speak(
                        R.string.report_fmt_battery,
                        controller.ac.batteryPercent.value,
                        locale = locale,
                    )
                },

                Command(
                    R.string.commands_return_home, respFmtExId
                ) { controller.fly { FlyToMe().act(this, userVM.metrics) } },
                Command(
                    R.string.command_follow_me,
                    respFmtExId,
                    R.string.commands_mission_follow_me_name
                ) {
                    controller.fly {
                        FollowMe(
                            cruiseHeight = 6.0,
                            followDistance = 3.0,
                            maxVelocity = 3.0,
                        ).act(this, userVM.metrics)
                    }
                },
                Command(
                    R.string.command_look_at_me, respFmtSimpleId
                ) { controller.fly { TrackMe().act(this, userVM.metrics) } },
            )
        )
        actionResolver = LlamaActionSequenceResolver(
            requireContext(),
            "qwen2.5-coder-1.5b-instruct-q4_0.gguf",
            listOf("he"),
            controllerVM.controller,
            userVM.metrics,
        )
        lifecycleScope.launch(Dispatchers.Default) { // todo: init in vm
            /*
            ToastUtils.showShortToast("AI is Loading...")
            try {
                actionResolver.init()
                ToastUtils.showShortToast("AI is Loaded!")
                playSfx(SFXManager.SFX.ACTION_CONFIRM)
            } catch (e: Exception) {
                Log.e("LlamaActionResolver", "error: ${e.message}", e)
                ToastUtils.showShortToast("AI Failed to Load: ${e.message}")
            }
            */

            ToastUtils.showShortToast("Finding Ground Station...")
            SubnetDevices.fromLocalAddress()
                .findDevices(object : SubnetDevices.OnSubnetDeviceFound {
                    override fun onDeviceFound(device: Device?) {}

                    override fun onFinished(devicesFound: ArrayList<Device?>?) {
                        val localAddress = getLocalIpAddress()
                        val devices = devicesFound
                            ?.filterNotNull()
                            ?.filter { it.ip != localAddress }
                            ?: emptyList()
                        Log.i("VoiceControlFragment", "found devices: $devices")
                        devices.firstOrNull()?.let {
                            ToastUtils.showShortToast("Connecting to ${it.ip}")
                            groundStationResolver.connect(it.ip)
                            SFXManager.playSfx(SFX.ACTION_CONFIRM)
                        } ?: run {
                            ToastUtils.showToast("No devices found on network.")
                            SFXManager.playSfx(SFX.NOTIFY_TECHNICAL)
                        }
                    }
                })
                .setTimeOutMillis(2000)
        }

        binding.btnMic.setOnClickListener { viewModel.toggleListening(locale) }

        viewModel.isListening.observe(viewLifecycleOwner) { enabled ->
            binding.btnMic.setImageResource(
                if (enabled) R.drawable.uxsdk_ic_customer_loading
                else R.drawable.ic_mic_white_36dp
            )
        }
        viewModel.speech.observe(viewLifecycleOwner) {
            binding.speech.text = it
        }
        viewModel.partialSpeech.observe(viewLifecycleOwner) {
            binding.speech.text = it
            // todo: handle partial results ui
        }
        viewModel.resolutionName.observe(viewLifecycleOwner) {
            binding.sttResult.text = it
        }

        val adapter = ResolverAdapter()
        binding.rvResolvers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResolvers.adapter = adapter
        viewModel.uiStates.observe(viewLifecycleOwner) { states ->
            adapter.submitList(states)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class ResolverAdapter : ListAdapter<
            ResolverViewState,
            ResolverAdapter.ViewHolder>(
        DiffCallback
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
            ItemResolverBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            ),
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(getItem(position))

        class ViewHolder(
            private val binding: ItemResolverBinding,
        ) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(viewState: ResolverViewState) {
                val data = viewState.item
                binding.tvName.setText(data.nameId)
                binding.ivIcon.setImageResource(data.iconId)

                val status = viewState.status
                val state = status.state
                val result = status.result

                binding.root.alpha = when {
                    state == SpeechResolversVM.State.ACTIVE || result != null -> 1.0f
                    else -> 0.3f
                }

                binding.prog.visibility =
                    if (state == SpeechResolversVM.State.ACTIVE && result == null)
                        View.VISIBLE
                    else View.GONE

                // Status Icon: Visible if result exists
                binding.ivStatus.visibility =
                    if (result != null) View.VISIBLE
                    else View.GONE

                result?.let { res ->
                    binding.ivStatus.setImageResource(
                        if (res.isSuccess) R.drawable.uxsdk_ic_alert_good
                        else R.drawable.uxsdk_ic_cancel_landing_disabled
                    )
                } ?: run {
                    binding.ivStatus.setImageResource(R.drawable.uxsdk_ic_customer_loading)
                }
            }
        }

        object DiffCallback : DiffUtil.ItemCallback<ResolverViewState>() {
            override fun areItemsTheSame(
                oldItem: ResolverViewState,
                newItem: ResolverViewState
            ): Boolean = oldItem.item.nameId == newItem.item.nameId

            override fun areContentsTheSame(
                oldItem: ResolverViewState,
                newItem: ResolverViewState
            ): Boolean = oldItem == newItem
        }
    }
}
