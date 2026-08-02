package com.kcg.dr.voice

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.kcg.dr.api.Action
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.AssetUtils.getAssetOrExtract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.encodeToString
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
    val json: Json get() = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(speech: String): T? = try {
        json.decodeFromString(serializer, speech)
    } catch (_: Exception) {
        null
    }

    companion object {
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

    suspend fun init(modelName: String) = coroutineScope {
        withContext(Dispatchers.IO) {
            try {
                Log.i("LlamaActionResolver", "schema: $schemas")
                val modelFile = context.getAssetOrExtract(
                    "models/$modelName"
                )
                engine.loadModel(modelFile.absolutePath)
                engine.setSystemPrompt(systemPrompt)
            } catch (e: Exception) {
                Log.e("LlamaActionResolver", "error: ${e.message}", e)
            }
        }
    }

    protected fun preProcess(speech: String): String = speech.trim().trimIndent()
    protected fun postProcess(result: String): String = SerialisedResolver.findJson(result) ?: result

    protected val schemas: String
        get() {
            val generator = SerializationClassJsonSchemaGenerator(json)
            // Sealed schema generation includes all subclasses of the sealed class
            val schema = generator.generateSchema(serializer.descriptor)
            val schemaString = schema.encodeToString(Json { prettyPrint = true })
            return schemaString
        }

    private suspend fun generateAndCollect(speech: String): String {
        engine.setSystemPrompt(systemPrompt)
        val resultFlow = engine.sendUserPrompt(speech)
        var result = ""
        resultFlow.collect {
            result += it
        }
        return result
    }

    override suspend fun resolve(speech: String): T? {
        val preProcessedSpeech = preProcess(speech)
        val t0 = System.currentTimeMillis()
        val result = generateAndCollect(preProcessedSpeech)
        Log.d(
            "LlamaSerialisedResolver",
            "generation took: ${(System.currentTimeMillis() - t0) / 100}s"
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

    fun destroy() {
        engine.destroy()
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

class LlamaActionSequenceResolver(context: Context) :
    LlamaSerialisedResolver<List<Action>>(context),
    SpeechExecutor<List<Action>, AircraftController, Unit> {
    override fun nameOf(t: List<Action>): String = t.joinToString { it.javaClass.simpleName }

    override suspend fun execute(t: List<Action>, arg: AircraftController?) {
        arg?.let { for (action in t) action.act(it) }
    }

    override val serializer: KSerializer<List<Action>> = ListSerializer(Action.serializer())
    override val systemPrompt: String =
        """
        # Motive:
        You are a speech-to-intent engine, translating user's speech from natural language into a list of actions,
        where each action is represented as a JSON object DTO.
        
        The user's speech is provided as the user prompt.
        
        ## Objective:
        - You, as a speech-to-intent engine, are tasked with translating the user's speech into a list of actions.
        - You are provided below the list of possible actions that the system can perform, and their JSON schemas.
        The user's speech intent could include a single system action from the list,
        or it could describe an action that requires a sequence of multiple system actions,
        in which case you should output the sequence as a JSON list of actions.
        - If you can adequately translate the user's speech into a single action,
        output it's JSON representation as a single item inside a JSON list.
        
        ## Action Schema constraints:
        - You are provided below JSON Schemas of all the possible actions that the system can perform.
        - When translating the user's speech into a list of actions, you must transform the user's speech
        to JSON Objects that match the System Action schemas and ONLY the schemas.
        DO NOT invent new actions or fields. Use ONLY the System Actions given in the schemas you're
        provided. Use the exact field names provided in the schemas.
        
        ## User Intent Inference:
        - Use context clues and information in the user's speech to help you understand the intent.
        - Use the JSON Schema descriptions to help you understand what each field represents,
        what values it can take and what values the user intended to be set.
        - Use information from the user's speech and your own reasoning to fill in the values of each field.
        - The JSON parser is equipped to handle default values for fields that are not explicitly set.
        Therefore, If a DTO field or property is not specified in the Schema as required, and the User
        speech does NOT explicitly or implicitly provide a value for that field, there is NO NEED
        to specify some default value for it and you should NOT include the field in the JSON output.
                
        # System Action Schemas:
        $schemas
        
        ## Json Formatting:
        - The JSON must be valid and parseable to a Java/Kotlin object.
        - Include a "type" field in the JSON object representing the serial name of the class.
        
        *Output ONLY the string of the JSON result, nothing else.*
        
        ### User's Speech:
    """.trimIndent()
}