package com.kcg.dr.voice

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.aviadl40.utils.android.getLocalizedResources
import com.kcg.dr.voice.SpeechResolver.Description
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SpeechResolver<T> {
    suspend fun resolve(speech: String, locale: Locale = Locale.getDefault()): T?

    data class Description(
        val name: String,
        val response: String = ""
    )

    fun describe(t: T, locale: Locale = Locale.getDefault()): Description
}

interface SpeechExecutor<T, R> : SpeechResolver<T> {
    fun execution(t: T): suspend () -> R

    suspend fun resolveToExecute(
        speech: String,
        locale: Locale = Locale.getDefault()
    ): Triple<T, suspend () -> R, Description>? =
        resolve(speech, locale)?.let {
            Triple(
                it,
                execution(it),
                describe(it, locale),
            )
        }

    suspend fun resolveAndExecute(speech: String, locale: Locale = Locale.getDefault()): R? =
        resolve(speech, locale)?.let { execution(it)() }
}

interface CandidateResolver<C, M> : SpeechResolver<Pair<C, M>> {
    val candidates: Collection<C>

    fun matches(candidate: C, speech: String, locale: Locale): M?

    override suspend fun resolve(speech: String, locale: Locale): Pair<C, M>? {
        candidates.forEach { c ->
            val match = matches(c, speech, locale)
            if (match != null)
                return c to match
        }
        return null
    }
}

interface CandidateExecutor<C, M, R> : SpeechExecutor<Pair<C, M>, R> {
    override fun execution(t: Pair<C, M>): suspend () -> R =
        execution(t.first, t.second)

    fun execution(candidate: C, match: M): suspend () -> R
}

interface RegexResolver<T> : CandidateResolver<T, MatchResult> {
    override fun matches(candidate: T, speech: String, locale: Locale): MatchResult? {
        val regex = candidate.toString().toRegex(RegexOption.IGNORE_CASE)
        return regex.find(speech)
    }
}

abstract class CommandResolver<A, M>(val context: Context) :
    CandidateResolver<CommandResolver.Command<A>, M> {
    data class Command<A>(
        val promptRegexStringId: Int,
        val responseFmtStringId: Int? = null,
        val nameStringId: Int? = null,
        val func: (A) -> Unit = { }
    ) {
        companion object {
            val respFmtSimpleId get() = R.string.commands_response_fmt_simple
            val respFmtExId get() = R.string.commands_response_fmt_executing
            val respFmtGoId get() = R.string.commands_response_fmt_going
        }

        fun prompt(resources: Resources): String = resources.getString(promptRegexStringId)

        fun name(resources: Resources): String =
            nameStringId?.let { resources.getString(it) }
                ?: prompt(resources).split("|").first()

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

class RegexCommandResolver(context: Context) :
    CommandResolver<MatchResult, MatchResult>(context),
    CandidateExecutor<CommandResolver.Command<MatchResult>, MatchResult, Unit> {
    override fun matches(
        candidate: Command<MatchResult>,
        speech: String,
        locale: Locale
    ): MatchResult? {
        return candidate.prompt(context.getLocalizedResources(locale))
            .toRegex(RegexOption.IGNORE_CASE)
            .find(speech)
    }

    override fun describe(t: Pair<Command<MatchResult>, MatchResult>, locale: Locale): Description {
        val lr = context.getLocalizedResources(locale)
        return Description(
            t.first.name(lr),
            t.first.response(lr) ?: ""
        )
    }

    override fun execution(
        candidate: Command<MatchResult>,
        match: MatchResult
    ): suspend () -> Unit =
        { candidate.func(match) }
}

interface SerialisedResolver<T> : SpeechResolver<T> {
    val serializer: KSerializer<T>
    val decoder: Json get() = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(speech: String, locale: Locale): T? = try {
        decoder.decodeFromString(serializer, speech)
    } catch (e: Exception) {
        Log.e("SerialisedResolver", "error decoding json: ${e.message}", e)
        null
    }
}

interface PipelineResolver<T> : SpeechResolver<T>, Closeable {
    interface Stage : SpeechResolver<String>, Closeable {
        override fun describe(t: String, locale: Locale): Description = Description(
            this::class.simpleName.toString(), ""
        )

        suspend fun init() {}

        override fun close() {}
    }

    val pipeline: List<Stage>

    override suspend fun resolve(speech: String, locale: Locale): T? {
        var text = speech
        pipeline.forEach {
            text = it.resolve(text, locale) ?: return null
        }
        return finalResolve(text, locale)
    }

    suspend fun finalResolve(speech: String, locale: Locale): T?

    suspend fun init() = pipeline.forEach { it.init() }

    override fun close() = pipeline.forEach { it.close() }
}


class CommandsRegexCanoniseStage(
    private val context: Context,
    private val prompts: Collection<Int>
) : PipelineResolver.Stage {
    override suspend fun resolve(speech: String, locale: Locale): String? {
        val lr = context.getLocalizedResources(locale)
        var text = speech.trim().trimIndent()
        Log.i("RegexStage", "canonising: $text")
        prompts.forEach { promptsId ->
            val promptsString = lr.getString(promptsId)
            if (!promptsString.contains("|")) return@forEach

            val canonical = promptsString.split("|").first().trim()
            val regex = promptsString.toRegex(RegexOption.IGNORE_CASE)
            try {
                text = regex.replace(text) { match ->
                    if (match.groups.isNotEmpty())
                        match.groups[1]
                            // If group 1 exists and isn't the start of the match, it's meant as a parameter (e.g. location)
                            ?.takeIf { it.range.first > match.range.first }
                            ?.let { g ->
                                val prefix =
                                    match.value.substring(0, g.range.first - match.range.first)
                                val suffix =
                                    match.value.substring(g.range.first - match.range.first)

                                val leadingSpace = prefix.takeWhile { it.isWhitespace() }
                                val trailingSpace = prefix.takeLastWhile { it.isWhitespace() }

                                return@replace leadingSpace + Regex.escapeReplacement(canonical) + trailingSpace + suffix
                            }

                    Regex.escapeReplacement(canonical)
                }
            } catch (e: Exception) {
                Log.e("RegexCanonise", "Error processing prompt $promptsId: ${e.message}")
            }
        }
        Log.i("RegexStage", "canonised: $text")
        return text
    }

}

class TranslatorStage(
    val translatedLanguages: List<String>,
    val targetLang: String = TranslateLanguage.ENGLISH
) :
    PipelineResolver.Stage, Closeable {
    companion object {
        const val TAG = "TranslatorStage"
    }

    private val translators = mutableMapOf<String, Translator>()

    override suspend fun init() {
        Log.d(TAG, "building translators... $translatedLanguages")
        translators.values.forEach { it.close() }
        translators.clear()
        translatedLanguages.forEach {
            if (it == targetLang) return@forEach
            Log.d(TAG, "building translator $it->$targetLang")
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(it)
                .setTargetLanguage(targetLang)
                .build()
            val translator = Translation.getClient(options)
            val t = System.currentTimeMillis()
            suspendCancellableCoroutine { cont -> // todo: upgrade to await from Google Play coroutines thing
                Log.d(TAG, "downloading model $it->$targetLang")
                translator
                    .downloadModelIfNeeded()
                    .addOnSuccessListener {
                        cont.resume(Unit)
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "error downloading model: ${e.message}", e)
                        cont.resumeWithException(e)
                    }.addOnCanceledListener {
                        Log.d(TAG, "download cancelled")
                        cont.resume(Unit)
                    }
            }
            Log.d(
                TAG,
                "model $it->$targetLang downloaded (took ${(System.currentTimeMillis() - t) / 1000}s)"
            )
            translators[it] = translator
        }
        Log.d(TAG, "translators built")
    }

    override suspend fun resolve(speech: String, locale: Locale): String {
        var text = speech
        text = text.trim().trimIndent()
        val lang = TranslateLanguage.fromLanguageTag(locale.language)
        Log.i(TAG, "lang from $locale (${locale.language}) : $lang -> $targetLang")
        translators[lang]?.let { tr ->
            text = suspendCancellableCoroutine { cont ->
                tr.translate(text)
                    .addOnSuccessListener {
                        Log.i(TAG, "translated: $it")
                        cont.resume(it)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "error translating: ${e.message}", e)
                        cont.resume(text) // skip translation if failed
                    }
            }
        } ?: Log.w(TAG, "no translator for $lang")
        return text
            .replace(" feet", " meters")
    }

    override fun close() = translators.values.forEach { it.close() }
}