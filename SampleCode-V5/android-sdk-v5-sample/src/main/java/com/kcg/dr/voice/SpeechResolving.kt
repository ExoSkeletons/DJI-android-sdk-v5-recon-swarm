package com.kcg.dr.voice

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.kcg.dr.api.actions.Action
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.AssetUtils.getAssetOrExtract
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import com.kcg.dr.voice.SerialisedResolver.Companion.appendPropertyShortJson
import com.kcg.dr.voice.SerialisedResolver.Companion.dereference
import com.kcg.dr.voice.SpeechResolver.Description
import io.ktor.http.parsing.ParseException
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
import java.util.Locale

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
                describe(it),
            )
        }

    suspend fun resolveAndExecute(speech: String, locale: Locale = Locale.getDefault()): R? =
        resolve(speech)?.let { execution(it)() }
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
            val properties =  (p as? PropertiesContainer)?.properties
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

        fun findJson(text: String): String? {
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

abstract class LlamaSerialisedResolver<T>(val context: Context) :
    SerialisedResolver<T>, Closeable {
    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)
    protected abstract val systemPrompt: String
    protected abstract val schema: String
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

    suspend fun init(modelName: String) {
        // todo: move to general init (modelName passed as text in constructor),
        //  then init in super and use in VM
        try {
            val modelFile = context.getAssetOrExtract("models/$modelName")
            engine.loadModel(modelFile.absolutePath)
            Log.d("LlamaActionResolver", "model loaded")
            Log.d("LlamaActionResolver", "setting system prompt...")
            val t1 = System.currentTimeMillis()
            engine.setSystemPrompt(systemPrompt)
            Log.d(
                "LlamaActionResolver",
                "system prompt set (took ${(System.currentTimeMillis() - t1) / 1000}s)"
            )
        } catch (e: Exception) {
            Log.e("LlamaActionResolver", "error: ${e.message}", e)
        }
    }

    protected fun preProcess(speech: String): String = speech.trim().trimIndent()
    protected fun postProcess(result: String): String =
        SerialisedResolver.findJson(result) ?: result

    private suspend fun generateAndCollect(speech: String): String {
        val resultFlow = engine.sendUserPrompt(speech)
        Log.i("LlamaActionResolver", "collecting result")
        var result = ""
        resultFlow.collect {
            result += it
        }
        return result
    }

    override suspend fun resolve(speech: String, locale: Locale): T? {
        val preProcessedSpeech = preProcess(speech)
        val t0 = System.currentTimeMillis()
        Log.d("LlamaSerialisedResolver", "generating")
        val result = generateAndCollect(preProcessedSpeech)
        Log.d(
            "LlamaSerialisedResolver",
            "generation took: ${(System.currentTimeMillis() - t0) / 1000}s"
        )
        val processedResult = postProcess(result)
        Log.d("LlamaSerialisedResolver", "parsed response:\n$processedResult")
        return super.resolve(processedResult, locale)
    }

    override fun close() = engine.destroy()
}

class ActionResolver(
    private val controller: AircraftController,
    private val device: UserMetrics,
) :
    SerialisedResolver<Action>,
    SpeechExecutor<Action, Unit> {
    override val serializer: KSerializer<Action> = Action.serializer()
    override fun describe(t: Action, locale: Locale): Description = Description(t.description)

    override fun execution(t: Action): suspend () -> Unit = { t.act(controller, device) }
}

class LlamaActionSequenceResolver(
    context: Context,
    private val controller: AircraftController,
    private val device: UserMetrics,
) :
    LlamaSerialisedResolver<List<Action>>(context),
    SpeechExecutor<List<Action>, Unit> {
    override val serializer: KSerializer<List<Action>> = ListSerializer(Action.serializer())
    public override val schema: String = buildString {
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

    override fun describe(t: List<Action>, locale: Locale): Description =
        Description(t.joinToString(", ") { it.description })

    override fun execution(t: List<Action>): suspend () -> Unit = {
        controller.safely {
            for (action in t)
                action.act(this, device)
        }
    }

    override val systemPrompt: String = "" +
            """
               # Role
    
               You are a speech-to-intent engine.
    
               Convert the user's natural language request into a JSON array of system actions.
    
               Each action must exactly match one of the JSON Schemas below.
    
               # Rules
    
               - The JSON Schemas below are the ONLY valid actions.
               - Never invent actions or fields. Never rephrase their names.
               - Use ONLY the available system actions and fields below.
               - Use the EXACT "type" value, field names & enum constants from the schemas.
               - Infer the user's intent and populate schema fields accordingly.
               - Use schema comments to infer a field's semantics.
               - If a field is optional and the user did not explicitly or implicitly specify a value, omit the field.
               - If no action matches, return null not an empty Array.
               - Output valid JSON only.
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