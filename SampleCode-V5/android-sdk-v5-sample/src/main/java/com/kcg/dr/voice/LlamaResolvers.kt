package com.kcg.dr.voice

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.kcg.dr.api.dto.actions.Action
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.appendPropertyShortJson
import com.kcg.dr.utils.dereference
import com.kcg.dr.utils.getAssetOrExtract
import com.kcg.dr.voice.SpeechResolver.Description
import dji.sampleV5.aircraft.R
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.util.Locale


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

abstract class LlamaSerialisedStage(context: Context, modelName: String) :
    LlamaResolver.LlamaAndroidStage(context, modelName) {
    companion object {
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

    protected abstract val schema: String
    override suspend fun postProcess(result: String): String =
        findJson(result) ?: result
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