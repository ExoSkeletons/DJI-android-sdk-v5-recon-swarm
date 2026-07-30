package com.kcg.dr.voice

import android.content.res.Resources
import com.kcg.dr.api.Action
import com.kcg.dr.flight.AircraftController
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

interface SpeechResolver<T> {
    fun resolve(speech: String): T
}

interface SpeechExecutor<T, A, R> : SpeechResolver<T?> {
    fun nameOf(t: T): String

    fun responseTo(t: T): String = ""

    suspend fun execute(t: T, arg: A? = null): R

    suspend fun resolveAndExecute(speech: String, arg: A? = null): R? =
        resolve(speech)?.let { execute(it, arg) }

    fun resolveToExecute(speech: String, arg: A? = null): Pair<T, suspend () -> R>? =
        resolve(speech)?.let { it to { execute(it, arg) } }
}

interface CandidateResolver<C, M> : SpeechResolver<Pair<C, M>?> {
    val candidates: Collection<C>

    fun matches(candidate: C, speech: String): M?

    override fun resolve(speech: String): Pair<C, M>? {
        candidates.forEach { c ->
            val match = matches(c, speech)
            if (match != null)
                return c to match
        }
        return null
    }
}

interface CandidateExecutor<C, M, R> : SpeechExecutor<Pair<C, M>, Unit, R> {
    override suspend fun execute(t: Pair<C, M>, arg: Unit?): R =
        execute(t.first, t.second)

    fun execute(candidate: C, match: M): R
}

interface RegexResolver<T> : CandidateResolver<T, MatchResult> {
    override fun matches(candidate: T, speech: String): MatchResult? {
        val regex = candidate.toString().toRegex(RegexOption.IGNORE_CASE)
        return regex.find(speech)
    }
}

abstract class RCommandResolver<A, M>(var resources: Resources) :
    CandidateResolver<RCommandResolver.Command<A>, M> {
    data class Command<A>(
        val promptRegexStringId: Int,
        val responseFmtStringId: Int? = null,
        val nameStringId: Int? = null,
        val func: (A) -> Unit = { }
    ) {
        fun prompt(resources: Resources): String = resources.getString(promptRegexStringId)

        fun name(resources: Resources): String =
            nameStringId?.let { resources.getString(it) } ?: prompt(resources)

        fun response(resources: Resources): String? =
            responseFmtStringId?.let { resources.getString(it, name(resources)) }
    }

    val commands: MutableList<Command<A>> = mutableListOf()
    override val candidates get() = commands

    fun setCommands(commands: Collection<Command<A>> = emptyList()) {
        candidates.clear()
        candidates.addAll(commands)
    }
}

class RegexCommandResolver(resources: Resources) :
    RCommandResolver<MatchResult, MatchResult>(resources),
    CandidateExecutor<RCommandResolver.Command<MatchResult>, MatchResult, Unit> {
    override fun matches(candidate: Command<MatchResult>, speech: String): MatchResult? {
        return candidate.prompt(resources)
            .toRegex(RegexOption.IGNORE_CASE)
            .find(speech)
    }

    override fun nameOf(t: Pair<Command<MatchResult>, MatchResult>): String =
        t.first.name(resources)

    override fun responseTo(t: Pair<Command<MatchResult>, MatchResult>): String =
        t.first.response(resources) ?: ""

    override fun execute(candidate: Command<MatchResult>, match: MatchResult) =
        candidate.func(match)
}

interface SerialisedResolver<T> : SpeechResolver<T?> {
    val serializer: KSerializer<T>
    val json: Json get() = Json { ignoreUnknownKeys = true }

    override fun resolve(speech: String): T? = try {
        json.decodeFromString(serializer, speech)
    } catch (_: Exception) {
        null
    }
}

class ActionResolver :
    SerialisedResolver<Action>,
    SpeechExecutor<Action, AircraftController, Unit> {
    override val serializer: KSerializer<Action> = Action.serializer()
    override fun nameOf(t: Action): String = t.javaClass.simpleName

    override suspend fun execute(t: Action, arg: AircraftController?) {
        arg?.let { t.act(it) }
    }
}