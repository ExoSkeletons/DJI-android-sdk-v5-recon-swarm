package com.kcg.dr.voice

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.kcg.dr.api.dto.actions.Action
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.AssetUtils.getAssetOrExtract
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import com.kcg.dr.voice.LlamaResolver.LlamaAndroidStage
import com.kcg.dr.voice.SerialisedResolver.Companion.appendPropertyShortJson
import com.kcg.dr.voice.SerialisedResolver.Companion.dereference
import com.kcg.dr.voice.SpeechResolver.Description
import dji.sampleV5.aircraft.R
import io.ktor.http.parsing.ParseException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.ArrayContainer
import kotlinx.schema.json.ArrayPropertyDefinition
import kotlinx.schema.json.BooleanPropertyDefinition
import kotlinx.schema.json.CommonSchemaAttributes
import kotlinx.schema.json.JsonSchema
import kotlinx.schema.json.NumericPropertyDefinition
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertiesContainer
import kotlinx.schema.json.PropertyDefinition
import kotlinx.schema.json.ReferencePropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.schema.json.ValuePropertyDefinition
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
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

    companion object {
        fun dereference(
            definition: PropertyDefinition,
            defs: Map<String, PropertyDefinition>
        ): PropertyDefinition =
            (definition as? ReferencePropertyDefinition)?.let {
                definition.ref?.let {
                    defs[it.substringAfterLast("/")]
                        ?: throw ParseException("Missing definition for Reference Property: $definition")
                } ?: throw ParseException("Missing ref field in Reference Property: $definition")
            } ?: definition

        fun StringBuilder.appendPropertyMarkdown(
            definition: PropertyDefinition,
            defs: Map<String, PropertyDefinition>,
            name: String? = null,
            required: Boolean = true,
            depth: Int = 0,
        ) {
            val indent = "\t".repeat(depth)
            val p = dereference(definition, defs)

            append(indent)
            if (depth > 0) append("- ")

            if (definition is StringPropertyDefinition && name == "type") {
                appendLine("type: ${definition.constValue}")
                return
            }

            append("${name}:")
            val (types, desc) = when (p) {
                /*is ObjectPropertyDefinition ->
                    ((p.properties?.get("type") as? StringPropertyDefinition)
                        ?.constValue?.toString()?.let {
                            listOf(it)
                        } ?: emptyList()) + p.type to p.description*/
                is ValuePropertyDefinition<*> -> (p.type ?: emptyList()) to p.description
                is JsonSchema -> p.type to p.description
                else -> throw ParseException("Invalid property type: ${definition::class}")
            }
            append(" ${types.joinToString("|")}")
            if (!required) append(" (optional)")
            appendLine()
            desc?.let { appendLine("$indent* Description\n$indent\t$it") }

            (p as? ObjectPropertyDefinition)?.properties?.takeIf { it.isNotEmpty() }?.let {
                appendLine("${indent}* Fields")
                it.forEach { (childName, childProperty) ->
                    appendPropertyMarkdown(
                        childProperty, defs,
                        childName,
                        p.required?.contains(childName) == true,
                        depth + 1
                    )
                }
            }
        }

        fun StringBuilder.appendPropertyShortJson(
            definition: PropertyDefinition,
            defs: Map<String, PropertyDefinition>,
            name: String? = null,
            required: Boolean = true,
            depth: Int = 0,
        ) {
            val indent = "\t".repeat(depth)
            val p = dereference(definition, defs)

            if (definition is StringPropertyDefinition && name == "type") {
                appendLine("${indent}\"type\": ${definition.constValue},")
                return
            }

            val desc = (p as? CommonSchemaAttributes)?.description
            val types = (p as? CommonSchemaAttributes)?.type
            val properties = (p as? PropertiesContainer)?.properties
            val req = (p as? PropertiesContainer)?.required
            val items = (p as? ArrayContainer)?.items
            val enum = when (p) {
                is ObjectPropertyDefinition -> p.enum
                is NumericPropertyDefinition -> p.enum
                is StringPropertyDefinition -> p.enum?.map { "\"$it\"" }
                is BooleanPropertyDefinition -> p.enum
                is ArrayPropertyDefinition -> p.enum
                is JsonSchema -> p.enum
                else -> null
            }

            desc?.let { appendLine("$indent// $it") }
            append(indent)
            name?.let { append("\"$it\": ") }
            types?.let { append(" ${it.joinToString("|")}") }
            enum?.let { append(" enum [${it.joinToString("|")}]") }
            if (!required) append(" (optional)")

            properties?.takeIf { it.isNotEmpty() }?.let {
                appendLine(" {")
                properties.forEach { (childName, childProperty) ->
                    appendPropertyShortJson(
                        childProperty, defs,
                        childName,
                        req?.contains(childName) == true,
                        depth + 1
                    )
                }
                append("$indent}")
            }
            items?.let {
                appendLine(" [")
                appendPropertyShortJson(items, defs, null, true, depth + 1)
                appendLine("${indent}\t...,")
                append("$indent]")
            }
            appendLine(",")
        }

        fun findJson(t: String): String? {
            var text = t
            text = text
                .substringAfterLast("```json")
                .substringBeforeLast("```")
                .replace(Regex("//[^\r\n]*"), "")

            val start = text.indexOfFirst { it == '{' || it == '[' }
            if (start == -1) return null

            val brackStack = ArrayDeque<Char>()
            var inString = false
            var escaped = false

            for (i in start until text.length) {
                when (val c = text[i]) {
                    '"' -> if (!escaped) inString = !inString
                    '\\' -> escaped = inString && !escaped
                    else -> {
                        escaped = false
                        if (!inString) when (c) {
                            '{' -> brackStack += '}'
                            '[' -> brackStack += ']'
                            '}', ']' -> {
                                if (brackStack.removeLastOrNull() != c)
                                    return null
                                if (brackStack.isEmpty())
                                    return text.substring(start, i + 1)
                            }
                        }
                    }
                }
            }

            return null
        }
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

abstract class LlamaResolver<T>(override val pipeline: List<PipelineResolver.Stage>) :
    PipelineResolver<T>, Closeable {
    companion object {
        const val TAG = "LlamaResolver"
    }

    interface LlamaStage : PipelineResolver.Stage, Closeable {
        val modelName: String
        val engine: InferenceEngine
        val systemPrompt: String
        suspend fun getModel(modelName: String): File

        override suspend fun init() {
            Log.d(TAG, "loading model $modelName...")
            if (engine.state.value.isModelLoaded) {
                Log.d(TAG, "model already loaded")
                return
            }

            engine.loadModel(getModel(modelName).absolutePath)
            Log.d(TAG, "model loaded")
            Log.d(TAG, "setting system prompt...")
            val t = System.currentTimeMillis()
            engine.setSystemPrompt(systemPrompt)
            Log.i(TAG, "system prompt set (took ${(System.currentTimeMillis() - t) / 1000}s)")
        }

        suspend fun preProcess(speech: String, locale: Locale): String = speech
        suspend fun postProcess(result: String): String = result
        suspend fun generateAndCollect(speech: String): String {
            try {
                val t0 = System.currentTimeMillis()
                val resultFlow = engine.sendUserPrompt(speech)
                Log.i(TAG, "collecting result")
                var result = ""
                var firstTokenTime = -1L
                resultFlow.collect {
                    if (firstTokenTime == -1L) {
                        firstTokenTime = System.currentTimeMillis()
                        Log.i(TAG, "Time to first token: ${firstTokenTime - t0}ms")
                    }
                    result += it
                }
                Log.i(TAG, "Total generation time: ${System.currentTimeMillis() - t0}ms")
                return result
            } catch (e: Exception) {
                Log.e(TAG, "error in llama generation: ${e.message}", e)
                return ""
            }
        }

        override suspend fun resolve(speech: String, locale: Locale): String {
            val preProcessed = preProcess(speech, locale)
            val generated = generateAndCollect(preProcessed)
            val postProcessed = postProcess(generated)
            return postProcessed
        }

        override fun close() = engine.destroy()
    }

    abstract class LlamaAndroidStage(val context: Context, override val modelName: String) :
        LlamaStage {
        override val engine: InferenceEngine = AiChat.getInferenceEngine(context)
        override suspend fun getModel(modelName: String): File =
            context.getAssetOrExtract("models/$modelName")
    }
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

abstract class LlamaSerialisedStage(context: Context, modelName: String) :
    LlamaAndroidStage(context, modelName) {
    protected abstract val schema: String
    override suspend fun postProcess(result: String): String =
        SerialisedResolver.findJson(result) ?: result
}

class LlamaActionSequenceResolver(
    context: Context,
    modelName: String,
    translatedLanguages: List<String>,
    private val controller: AircraftController,
    private val device: UserMetrics,
) :
    PipelineResolver<List<Action>>,
    SerialisedResolver<List<Action>>,
    SpeechExecutor<List<Action>, Unit> {
    override val serializer: KSerializer<List<Action>> = ListSerializer(Action.serializer())
    override val decoder: Json
        get() = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            @OptIn(ExperimentalSerializationApi::class)
            allowTrailingComma = true
            @OptIn(ExperimentalSerializationApi::class)
            allowComments = true
            @OptIn(ExperimentalSerializationApi::class)
            decodeEnumsCaseInsensitive = true
        }

    class ActionSequenceLlamaStage(
        context: Context, modelName: String
    ) : LlamaSerialisedStage(context, modelName) {
        override val schema: String = buildString {
            val generator = SerializationClassJsonSchemaGenerator(Json.Default)
            val schema = generator.generateSchema(Action.serializer().descriptor)
            val defs = schema.defs ?: emptyMap()
            schema.oneOf?.forEach { definition ->
                val aDef = dereference(definition, defs)
                appendPropertyShortJson(
                    aDef, defs,
                    ((aDef as? ObjectPropertyDefinition)
                        ?.properties?.get("type")
                            as? StringPropertyDefinition)
                        ?.constValue.toString().removeSurrounding("\"")
                )
                appendLine()
            }
        }

        override val systemPrompt: String = "" +
                """
               # Role
    
               You are a speech-to-intent engine.
               
               The user wants to perform a sequence of one or more actions.
    
               Translate & Convert the user's natural language request into a JSON array of system actions.
    
               Each action must exactly match one of the JSON Schemas below.
    
               # Rules
    
               - The JSON Schemas below are the ONLY valid actions.
               - Never invent actions or fields. Never rephrase their names.
               - Use ONLY the available system actions and fields below.
               - Use the EXACT "type" value, field names & enum constants from the schemas.
               - Output valid JSON Array only.
              
              # Semantics
              
               - Infer the user's intent and populate schema fields accordingly.
               - If field is optional and the user did not explicitly or implicitly specify a value, you must omit the field.
               - Comments in the input Schema provide each field's semantics. Don't output comments.
               - Grammar in request like "x and y", "x then y", "do x, y" hints at multiple actions.
                    -- for ex.: "takeoff, fly forward ... then fly upwards ... and then ..." is multiple actions.
            """.trimIndent() +
                "\n" + "\n" +
                "# Available Actions\n" +
                schema + "\n" +
                "\n" +
                """
               # Output
    
               Return ONLY the JSON array.
            """.trimIndent()
    }

    val regularisationStage = CommandsRegexCanoniseStage(
        context, setOf(
            R.string.command_hello,
            R.string.command_takeoff,
            R.string.command_land,
            R.string.command_spin,
            R.string.command_go_up,
            R.string.command_go_down,
            R.string.command_go_forward,
            R.string.command_go_backward,
            R.string.command_go_left,
            R.string.command_go_right,
            R.string.command_cam_fan,
            R.string.command_circle,
            R.string.command_square,
            R.string.command_follow_me,
            R.string.command_follow_target,
            R.string.command_mission_recon,
            R.string.command_mission_scan,
        )
    )
    override val pipeline: List<PipelineResolver.Stage> = listOf(
        regularisationStage,
        TranslatorStage(translatedLanguages),
        ActionSequenceLlamaStage(context, modelName),
    )

    override suspend fun resolve(speech: String, locale: Locale): List<Action>? =
        super<PipelineResolver>.resolve(speech, locale)

    override suspend fun finalResolve(speech: String, locale: Locale): List<Action>? =
        super<SerialisedResolver>.resolve(speech, locale)

    override fun describe(t: List<Action>, locale: Locale): Description =
        Description(t.joinToString(", ") { it.description })

    override fun execution(t: List<Action>): suspend () -> Unit = {
        controller.safely {
            for (action in t)
                action.act(this, device)
        }
    }
}