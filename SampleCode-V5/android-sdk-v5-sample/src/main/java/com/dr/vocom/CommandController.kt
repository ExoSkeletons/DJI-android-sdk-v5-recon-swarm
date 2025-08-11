package com.dr.vocom

import android.content.Context
import android.content.res.Resources
import java.util.Locale

class CommandController(val config: ParseConfig) {
    data class ParseConfig(
        val matchContained: Boolean = true,
        val noSpaces: Boolean = true,
    )

    data class Command(
        val name: String,
        val promptsStringId: Int,
        val func: () -> Unit = { }
    ) {
        fun strings(resources: Resources): List<String> {
            return resources.getString(promptsStringId).split("|")
        }
    }

    val commands = mutableListOf<Command>()

    fun resolve(speech: String, resources: Resources, locale: Locale = Locale.getDefault()): Command? {
        val matchedCommandContained = mutableSetOf<Command>()
        val matchedCommandExact = mutableSetOf<Command>()

        var cleanedSpeech = speech

        commands.forEach { com ->
            println(com.name)
            // TODO: replace | with regex string res
            com.strings(resources).forEach { comTxt ->
                var matchCom = comTxt
                if (config.noSpaces) {
                    matchCom = matchCom.replace(" ", "")
                    cleanedSpeech = cleanedSpeech.replace(" ", "")
                }
                val contained = cleanedSpeech.lowercase(locale).contains(matchCom)
                val exact = cleanedSpeech.lowercase(locale).contentEquals(matchCom)
                print("$comTxt c?$contained ex?$exact, ")
                if (contained) matchedCommandContained += com
                if (exact) matchedCommandExact += com
            }
            println()
        }

        return when {
            matchedCommandExact.isNotEmpty() -> matchedCommandExact.first()
            matchedCommandContained.isNotEmpty() && config.matchContained -> matchedCommandContained.first()
            else -> null
        }
    }
}