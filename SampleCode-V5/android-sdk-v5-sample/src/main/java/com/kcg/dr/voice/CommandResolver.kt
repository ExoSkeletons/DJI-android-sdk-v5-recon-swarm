package com.kcg.dr.voice

import android.content.res.Resources

class CommandResolver {
    data class Command(
        val promptRegexStringId: Int,
        val responseFmtStringId: Int? = null,
        val nameStringId: Int? = null,
        val func: (MatchResult) -> Unit = { }
    ) {
        fun response(resources: Resources): String? {
            val name = name(resources)
            return responseFmtStringId?.let { resources.getString(it, name) }
        }

        fun name(resources: Resources): String {
            nameStringId?.let { return resources.getString(it) }
            return resources.getString(promptRegexStringId).let {
                it.split("|").firstOrNull() ?: it
            }
        }
    }

    val commands = mutableListOf<Command>()

    fun resolve(
        speech: String,
        resources: Resources
    ): Pair<Command, MatchResult>? {
        commands.forEach { com ->
            val regex = resources.getString(com.promptRegexStringId)
                .toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(speech)
            if (match != null)
                return com to match
        }
        return null
    }
}
