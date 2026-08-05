package com.kcg.dr.voice

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.kcg.dr.api.Action
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.AssetUtils.getAssetOrExtract
import com.kcg.dr.voice.SerialisedResolver.Companion.appendPropertyShortJson
import com.kcg.dr.voice.SerialisedResolver.Companion.dereference
import io.ktor.http.parsing.ParseException
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.ArrayPropertyDefinition
import kotlinx.schema.json.BooleanPropertyDefinition
import kotlinx.schema.json.JsonSchema
import kotlinx.schema.json.NumericPropertyDefinition
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyDefinition
import kotlinx.schema.json.ReferencePropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.schema.json.ValuePropertyDefinition
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface SpeechResolver<T> {
    suspend fun resolve(speech: String): T
}

interface SpeechExecutor<T, A, R> : SpeechResolver<T?> {
    fun nameOf(t: T): String

    fun responseTo(t: T): String = ""

    suspend fun execute(t: T, arg: A? = null): R

    suspend fun resolveAndExecute(speech: String, arg: A? = null): R? =
        resolve(speech)?.let { execute(it, arg) }

    suspend fun resolveToExecute(speech: String, arg: A? = null): Pair<T, suspend () -> R>? =
        resolve(speech)?.let { it to { execute(it, arg) } }
}

interface CandidateResolver<C, M> : SpeechResolver<Pair<C, M>?> {
    val candidates: Collection<C>

    fun matches(candidate: C, speech: String): M?

    override suspend fun resolve(speech: String): Pair<C, M>? {
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
    val decoder: Json get() = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(speech: String): T? = try {
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

            val types = when (p) {
                is ValuePropertyDefinition<*> -> p.type
                else -> null
            }
            val desc = when (p) {
                is ObjectPropertyDefinition -> p.description
                is ValuePropertyDefinition<*> -> p.description
                is JsonSchema -> p.description
                else -> null
            }
            val enum = when (p) {
                is ObjectPropertyDefinition -> p.enum
                is NumericPropertyDefinition -> p.enum
                is StringPropertyDefinition -> p.enum?.map { "\"$it\"" }
                is BooleanPropertyDefinition -> p.enum
                is ArrayPropertyDefinition -> p.enum
                is JsonSchema -> p.enum
                else -> null
            }

            desc?.let { appendLine("$indent// Description: $it") }
            append("$indent\"${name}\"")
            types?.let { append(": ${it.joinToString("|")}") }
            enum?.let { append(" enum [${it.joinToString("|")}]") }
            if (!required) append(" (optional)")

            (p as? ObjectPropertyDefinition)?.properties?.takeIf { it.isNotEmpty() }?.let {
                appendLine(": {")
                it.forEach { (childName, childProperty) ->
                    appendPropertyShortJson(
                        childProperty, defs,
                        childName,
                        p.required?.contains(childName) == true,
                        depth + 1
                    )
                }
                append("$indent}")
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

abstract class LlamaSerialisedResolver<T>(val context: Context) : SerialisedResolver<T> {
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
        try {
            val modelFile = context.getAssetOrExtract(
                "models/$modelName"
            )
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

    override suspend fun resolve(speech: String): T? {
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
        return try {
            json.decodeFromString(serializer, processedResult)
        } catch (e: Exception) {
            Log.e("LlamaSerialisedResolver", "error parsing response: $e", e)
            null
        }
    }

    fun destroy() = engine.destroy()
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

class LlamaActionSequenceResolver(context: Context) :
    LlamaSerialisedResolver<List<Action>>(context),
    SpeechExecutor<List<Action>, AircraftController, Unit> {

    override suspend fun execute(t: List<Action>, arg: AircraftController?) {
        arg?.safely {
            for (action in t)
                action.act(this)
        }
    }

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

    override val serializer: KSerializer<List<Action>> = ListSerializer(Action.serializer())
    override fun nameOf(t: List<Action>): String = t.joinToString(", ") { it.description }
    override val systemPrompt: String = "" +
            """
               # Role
    
               You are a speech-to-intent engine.
    
               Convert the user's natural language request into a JSON array of system actions.
    
               Each action must exactly match one of the JSON Schemas below.
    
               # Rules
    
               - The JSON Schemas below are the ONLY valid actions.
               - Never invent actions or fields.
               - Use the EXACT "type" value and field names from the schemas.
               - Infer the user's intent and populate schema fields accordingly.
               - If a field is optional and the user did not explicitly or implicitly specify a value, omit the field.
               - A single action must still be returned as a JSON array containing one object.
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