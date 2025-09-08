package com.kcg.dr.vocom

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kcg.dr.LocaleUtils
import com.kcg.dr.vocom.CommandResolver.Command
import dji.sampleV5.aircraft.R
import java.util.Locale

class VoiceFragment : Fragment() {
    class CommandAdapter(
        private val fragment: VoiceFragment,
        private val commandList: List<Command> = listOf()
    ) :
        RecyclerView.Adapter<CommandAdapter.CommandViewHolder>() {

        class CommandViewHolder(val commandButton: Button) : RecyclerView.ViewHolder(commandButton)

        var selectedCom: Command? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommandViewHolder {
            val textView = Button(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
                setTextColor(resources.getColor(R.color.black, context.theme))
                textSize = 20f
            }
            return CommandViewHolder(textView)
        }

        override fun onBindViewHolder(holder: CommandViewHolder, position: Int) {
            val command = commandList[position]
            val strings = command.strings(
                LocaleUtils.getLocalizedResources(
                    holder.itemView.context,
                    fragment.locale
                )
            )

            holder.commandButton.setOnClickListener { fragment.execCom(command) }
            holder.commandButton.text = buildString {
                append(strings.first())
                append("\t (")
                append(command.name)
                append(")")
            }
            holder.commandButton.setTypeface(
                null,
                if (command == selectedCom) Typeface.BOLD_ITALIC
                else Typeface.NORMAL
            )
        }

        override fun getItemCount(): Int = commandList.size
    }


    var onCommand: (Command) -> Unit = { }
    private lateinit var comListAdapter: CommandAdapter
    private lateinit var controller: CommandResolver
    private val locale = Locale.getDefault()

    private lateinit var root: View

    private lateinit var speechRecognizerLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        speechRecognizerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val spokenText = result.data!!
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.get(0)

                if (spokenText != null) onHearText(spokenText)
                else
                    root.findViewById<TextView>(R.id.txtSpeechResult).text =
                        getString(R.string.error_speech_unrecognised)
            }
        }

        val rootView = inflater.inflate(R.layout.fragment_voice, container, false)
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.command_list)

        comListAdapter = CommandAdapter(this)
        controller = CommandResolver(CommandResolver.ParseConfig())

        rootView.findViewById<ImageButton>(R.id.btnMic).setOnClickListener { startListening() }

        rootView.findViewById<TextView>(R.id.locale_text).text = Locale.getDefault().toString()
        rootView.findViewById<Button>(R.id.locale_button_en)
            .setOnClickListener { LocaleUtils.setLocale(this, Locale.ENGLISH) }
        rootView.findViewById<Button>(R.id.locale_button_he)
            .setOnClickListener { LocaleUtils.setLocale(this, Locale("he", "IL")) }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = comListAdapter

        root = rootView
        return rootView
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
                val bias = listOf<Command>()
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
            root.findViewById<TextView>(R.id.commandResult).text =
                getString(R.string.error_speech_unrecognised)
            Log.e("VoiceFragment", "Error starting speech recognition: ${e.message}")
        }
    }

    private fun onHearText(spokenText: String) {
        root.findViewById<TextView>(R.id.txtSpeechResult).text = spokenText

        val com = controller.resolve(
            spokenText,
            LocaleUtils.getLocalizedResources(requireContext(), locale)
        )
        com?.let { execCom(it) }
    }

    private fun execCom(command: Command) {
        root.findViewById<TextView>(R.id.commandResult).text = command.name

        comListAdapter.selectedCom = command
        comListAdapter.notifyDataSetChanged()

        onCommand(command)
    }
}