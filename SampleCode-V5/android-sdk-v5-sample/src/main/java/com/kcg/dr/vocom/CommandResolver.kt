package com.kcg.dr.vocom

import android.content.res.Resources
import java.util.Locale

class CommandResolver(val config: ParseConfig) {
    data class ParseConfig(
        val matchContained: Boolean = true,
        val noSpaces: Boolean = true,
    )

    data class Command(
        val name: String,
        val promptsStringId: Int,
        val responseFmtStringId: Int? = null,
        val func: (String) -> Unit = { }
    ) {
        fun strings(resources: Resources): List<String> {
            return resources.getString(promptsStringId).split("|")
        }

        fun response(resources: Resources): String {
            val arg = strings(resources).firstOrNull() ?: name
            return if (responseFmtStringId == null) arg
            else resources.getString(responseFmtStringId, arg)
        }
    }

    val commands = mutableListOf<Command>()

    fun resolve(
        speech: String,
        resources: Resources,
        locale: Locale = Locale.getDefault()
    ): Command? {
        val matchedCommandContained = mutableSetOf<Command>()
        val matchedCommandExact = mutableSetOf<Command>()

        var cleanedSpeech = speech

        commands.forEach { com ->
            // TODO: replace | with regex string res
            com.strings(resources).forEach { comTxt ->
                var matchCom = comTxt
                if (config.noSpaces) {
                    matchCom = matchCom.replace(" ", "")
                    cleanedSpeech = cleanedSpeech.replace(" ", "")
                }
                val contained = cleanedSpeech.lowercase(locale).contains(matchCom)
                val exact = cleanedSpeech.lowercase(locale).contentEquals(matchCom)
                if (contained) matchedCommandContained += com
                if (exact) matchedCommandExact += com
            }
        }

        return when {
            matchedCommandExact.isNotEmpty() -> matchedCommandExact.first()
            matchedCommandContained.isNotEmpty() && config.matchContained -> matchedCommandContained.first()
            else -> null
        }
    }
}