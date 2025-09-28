/*
  - Generalized key registry and executor to support multiple key groups
  - Registry map is now keyed by (groupName, keyName) pairs
  - registerKeyClass(cls, groupName) lets you register any SDK Key class (e.g. FlightControllerKey, GimbalKey, CameraKey)
  - Executor uses the registry lookup by group+name and is no longer limited to FlightControllerKey
  - ConversionRegistry now prefers DJIValue.toJson() when available (no duplication of DTOs)

  NOTES:
  - Add more key groups by calling
      FlightKeyRegistry.registerKeyClass(MyKeyClass::class.java, "MyKeyGroupName")
  - DJIValue provides toJson() for serialization; ConversionRegistry will use it when available.
*/
@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.remote_api

import android.util.Log
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.base.DJIValue
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.set
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject


@Serializable
enum class DJIKeyFunc { GET, SET, ACTION }

@Serializable
data class DJIKeyRequest(
    val group: String,
    val name: String,
    val func: DJIKeyFunc? = null,
    val param: JsonObject? = null,
)

fun errorResponse(error: String) = Response(ok = false, error = error, errorCode = "")
fun exceptionResponse(t: Throwable) =
    Response(ok = false, error = t.message, errorCode = "${t.javaClass.simpleName}")

fun errorResponseDji(error: IDJIError) =
    Response(ok = false, error = error.errorCode(), errorCode = error.errorCode())


@Suppress("UNCHECKED_CAST")
class KeyItem<P, R>(
    val djiKey: DJIKey<R>,
    val djiKeySet: DJIKey<P> = djiKey as DJIKey<P>
) {

    fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JSONArray -> {
            val list = mutableListOf<JsonElement>()
            for (i in 0 until value.length()) {
                list += toElement(value.opt(i))
            }
            JsonArray(list)
        }

        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is JSONObject -> value.toJsonObject() // Recursive
        else -> JsonPrimitive(value.toString()) // fallback
    }

    fun JSONObject.toJsonObject(): JsonObject {
        val content = mutableMapOf<String, JsonElement>()
        for (key in this.keys())
            content[key] = toElement(this.opt(key))
        return JsonObject(content)
    }

    fun fromJson(jsonObject: JsonObject?): P? =
        djiKey.keyInfo.typeConverter.fromStr(jsonObject.toString()) as P?

    suspend fun get(): Response = suspendCancellableCoroutine { cont ->
        djiKey.get(
            {
                cont.resumeWith(
                    Result.success(
                        Response(
                            result = when (it) {
                                null -> JsonNull
                                is DJIValue -> it.toJson().toJsonObject()
                                else -> toElement(it)
                            }
                        )
                    )
                )
            },
            { cont.resumeWith(Result.success(errorResponseDji(it))) }
        )
    }

    suspend fun set(jsonParam: JsonObject?): Response {
        val p: P? = fromJson(jsonParam)
        if (p == null) return Response(ok = false, error = "Parameter cannot be null")

        return suspendCancellableCoroutine { cont ->
            djiKeySet.set(
                p,
                { cont.resumeWith(Result.success(Response())) },
                { cont.resumeWith(Result.success(errorResponseDji(it))) }
            )
        }
    }

    suspend fun action(jsonParam: JsonObject?): Response {
        val p: P? = fromJson(jsonParam)
        if (p == null) return errorResponse("Parameter cannot be null")

        return suspendCancellableCoroutine { cont ->
            (djiKey as DJIKey.ActionKey<P, R>).action(
                p,
                {
                    cont.resumeWith(
                        Result.success(
                            Response(
                                result = when (it) {
                                    null -> JsonNull
                                    is DJIValue -> it.toJson().toJsonObject()
                                    else -> toElement(it)
                                }
                            )
                        )
                    )
                },
                { cont.resumeWith(Result.success(errorResponseDji(it))) }
            )
        }
    }
}

object KeyActivator {
    fun String.normaliseKey() = this
        .replace("key", "", ignoreCase = true)
        .replace(Regex("([a-z])([A-Z]+)"), "$1_$2")
        .lowercase()

    val registry = mutableMapOf<Pair<String, String>, KeyItem<*, *>>()

    fun <T> registerKey(group: String, name: String, djiKeyInfo: DJIKeyInfo<T>) {
        registry[Pair(group.normaliseKey(), name.normaliseKey())] =
            KeyItem<T, T>(djiKeyInfo.create())
    }

    @Suppress("UNCHECKED_CAST")
    fun <P, R> registerKey(group: String, name: String, djiActionKeyInfo: DJIActionKeyInfo<P, R>) {
        registry[Pair(group.normaliseKey(), name.normaliseKey())] =
            KeyItem<P, R>(djiActionKeyInfo.create() as DJIKey<R>)
    }

    init {
        registerKey("FlightControllerKey", "KeyStartTakeoff", FlightControllerKey.KeyStartTakeoff)
        registerKey("FlightControllerKey", "KeyStopTakeoff", FlightControllerKey.KeyStopTakeoff)
        registerKey(
            "FlightControllerKey", "KeyStartLanding",
            FlightControllerKey.KeyStartAutoLanding
        )
        registerKey("FlightControllerKey", "KeyStopLanding", FlightControllerKey.KeyStopAutoLanding)
        registerKey(
            "FlightControllerKey", "KeyAircraftLocation",
            FlightControllerKey.KeyAircraftLocation
        )
        registerKey(
            "FlightControllerKey", "KeyAircraftLocation3D",
            FlightControllerKey.KeyAircraftLocation3D
        )
        registerKey(
            "FlightControllerKey", "KeyAircraftAttitude",
            FlightControllerKey.KeyAircraftAttitude
        )
        registerKey(
            "FlightControllerKey", "KeyAircraftBindingState",
            FlightControllerKey.KeyAircraftBindingState
        )
        registerKey(
            "FlightControllerKey", "KeyBatteryPercent",
            FlightControllerKey.KeyBatteryPowerPercent
        )
        registerKey("FlightControllerKey", "KeyAircraftName", FlightControllerKey.KeyAircraftName)
        registerKey("FlightControllerKey", "KeyFlightMode", FlightControllerKey.KeyFlightMode)

        registerKey("GimbalKey", "KeyGimbalReset", GimbalKey.KeyGimbalReset)
        registerKey("GimbalKey", "KeyRotateByAngle", GimbalKey.KeyRotateByAngle)
        registerKey("GimbalKey", "KeyGimbalAttitude", GimbalKey.KeyGimbalAttitude)
        registerKey("GimbalKey", "KeyGimbalMode", GimbalKey.KeyGimbalMode)
    }

    suspend fun handleKeyRequest(jsonObject: JsonObject): Response {
        // Decode serialised key request
        val keyDTO = try {
            Json.decodeFromString<DJIKeyRequest>(jsonObject.toString())
        } catch (e: Exception) {
            return exceptionResponse(e)
        }

        Log.d("KeyExecutor", "Executing key $keyDTO")
        Log.d("KeyExecutor", "Registry: $registry")

        // Lookup key in registry
        val keyItem = registry[Pair(keyDTO.group.normaliseKey(), keyDTO.name.normaliseKey())]
        if (keyItem == null) return errorResponse("Key not found")

        Log.d("KeyExecutor", "Matched key ${keyItem.djiKey}")

        val keyInfo = keyItem.djiKey.keyInfo

        // Verify key's functionality support
        when {
            keyDTO.func == DJIKeyFunc.GET && !keyInfo.isCanGet -> return errorResponse(
                "key ${keyDTO.name} does not support GET"
            )

            keyDTO.func == DJIKeyFunc.SET && !keyInfo.isCanSet -> return errorResponse(
                error = "key ${keyDTO.name} does not support SET"
            )

            keyDTO.func == DJIKeyFunc.ACTION && !keyInfo.isCanPerformAction -> return errorResponse(
                error = "key ${keyDTO.name} does not support ACTION"
            )
        }

        val func: DJIKeyFunc = keyDTO.func
            ?: when {
                keyInfo.isCanPerformAction -> DJIKeyFunc.ACTION // Prioritise action
                keyDTO.param == null && keyInfo.isCanGet -> DJIKeyFunc.GET // If no param given - prioritise get
                keyInfo.isCanSet -> DJIKeyFunc.SET
                keyInfo.isCanGet -> DJIKeyFunc.GET
                else -> return errorResponse("key ${keyDTO.name} does not support any function")
            }

        // Execute (keyItem get/set/action owns it's parameter Typing)
        return try {
            when (func) {
                DJIKeyFunc.GET -> keyItem.get()
                DJIKeyFunc.SET -> keyItem.set(keyDTO.param)
                DJIKeyFunc.ACTION -> keyItem.action(keyDTO.param)
            }
        } catch (e: Exception) {
            exceptionResponse(e)
        }
    }
}