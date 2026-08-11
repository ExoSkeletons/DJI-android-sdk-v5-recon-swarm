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
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.location.UserVM
import com.kcg.dr.utils.ResourcesManager
import com.kcg.dr.voice.SpeechResolversVM.ResolverViewState
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomVoiceControlBinding
import dji.sampleV5.aircraft.databinding.ItemResolverBinding
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceControlFragment : Fragment() {
    private var _binding: FragVocomVoiceControlBinding? = null
    private val binding get() = _binding!!

    private lateinit var commandResolver: RegexCommandResolver
    private lateinit var actionResolver: LlamaActionSequenceResolver

    private val stickVM: VirtualStickVM by activityViewModels()
    private val controllerVM: AircraftControlVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(AircraftControlVM.STICK_VM_KEY, stickVM)
            }
        },
        { AircraftControlVM.Factory }
    )
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
                        actionResolver to SpeechResolversVM.ResolverItem(
                            R.string.commands_parser_llm,
                            R.drawable.ic_llm_brain
                        )
                    )
                )
            }
        },
        { SpeechResolversVM.Factory }
    )
    private val locale get() = ResourcesManager.locale

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomVoiceControlBinding.inflate(inflater, container, false)
        ResourcesManager.setLocale(requireContext(), Locale("he", "IL"))
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ResourcesManager.setLocale(requireContext(), Locale("he", "IL"))

        commandResolver = RegexCommandResolver(requireContext())
        commandResolver.setCommands(
            listOf(
                CommandResolver.Command(
                    R.string.command_hello,
                    R.string.commands_response_fmt_simple,
                ) {
                    ToastUtils.showShortToast("hello!")
                }
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
            ToastUtils.showShortToast("AI is Loading...")
            try {
                actionResolver.init()
                ToastUtils.showShortToast("AI is Loaded!")
            } catch (e: Exception) {
                Log.e("LlamaActionResolver", "error: ${e.message}", e)
                ToastUtils.showShortToast("AI Failed to Load: ${e.message}")
            }
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
            )
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(getItem(position))

        class ViewHolder(private val binding: ItemResolverBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(viewState: ResolverViewState) {
                val data = viewState.item
                binding.tvName.setText(data.nameId)
                binding.ivIcon.setImageResource(data.iconId)

                val status = viewState.status
                val state = status.state
                val result = status.result
                binding.root.alpha =
                    if (state == SpeechResolversVM.State.ACTIVE || result != null) 1.0f
                    else 0.5f

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
