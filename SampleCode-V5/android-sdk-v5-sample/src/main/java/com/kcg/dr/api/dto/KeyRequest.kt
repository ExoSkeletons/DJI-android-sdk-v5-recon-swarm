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

package com.kcg.dr.api.dto

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.kcg.dr.api.toElement
import com.kcg.dr.api.toJsonElement
import com.kcg.dr.utils.CoroutineUtils.actionOrExcept
import com.kcg.dr.utils.CoroutineUtils.getOrExcept
import com.kcg.dr.utils.CoroutineUtils.setOrExcept
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.base.DJIValue
import dji.v5.et.create
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject


@Serializable
enum class DJIKeyFunc { GET, SET, ACTION }

@Serializable
data class DJIKeyRequest(
    @SerializedName("group")
    val group: String,
    @SerializedName("key")
    val key: String,
    @SerializedName("func")
    val keyType: DJIKeyFunc? = null,
    @SerializedName("args")
    val param: JsonObject? = null,
)


@Suppress("UNCHECKED_CAST")
class KeyItem<P, R>(
    val djiKey: DJIKey<R>,
    private val djiKeySet: DJIKey<P> = djiKey as DJIKey<P>
) {
    fun fromJson(jsonObject: JsonObject?): P? =
        djiKey.keyInfo.typeConverter.fromStr(jsonObject.toString()) as P?

    suspend fun get(): JsonElement {
        val r = djiKey.getOrExcept()
        return when (r) {
            null -> JsonNull
            is DJIValue -> r.toJson().toJsonElement()
            else -> r.toElement()
        }
    }

    suspend fun set(jsonParam: JsonObject?): JsonElement {
        val p: P? = fromJson(jsonParam)
        require(p != null) { "Parameter cannot be null" }

        djiKeySet.setOrExcept(p)
        return JsonNull
    }

    suspend fun action(jsonParam: JsonObject?): JsonElement {
        val p: P? = fromJson(jsonParam)
        require(p != null) { "Parameter cannot be null" }

        val r = (djiKey as DJIKey.ActionKey<P, R>).actionOrExcept(p)
        return when (r) {
            null -> JsonNull
            is DJIValue -> r.toJson().toJsonElement()
            else -> r.toElement()
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

    suspend fun handleKeyRequest(element: JsonElement): JsonElement {
        // Decode serialised key request
        val keyDTO = Json.decodeFromString<DJIKeyRequest>(element.toString())

        Log.d("KeyExecutor", "Executing key $keyDTO")
        Log.d("KeyExecutor", "Registry: $registry")

        // Lookup key in registry
        val keyItem = registry[Pair(keyDTO.group.normaliseKey(), keyDTO.key.normaliseKey())]
        if (keyItem == null) throw IllegalArgumentException("Key not found")

        Log.d("KeyExecutor", "Matched key ${keyItem.djiKey}")

        val keyInfo = keyItem.djiKey.keyInfo

        // Verify key's functionality support
        when {
            keyDTO.keyType == DJIKeyFunc.GET && !keyInfo.isCanGet -> throw UnsupportedOperationException(
                "key ${keyDTO.key} does not support GET"
            )

            keyDTO.keyType == DJIKeyFunc.SET && !keyInfo.isCanSet -> throw UnsupportedOperationException(
                "key ${keyDTO.key} does not support SET"
            )

            keyDTO.keyType == DJIKeyFunc.ACTION && !keyInfo.isCanPerformAction -> throw UnsupportedOperationException(
                "key ${keyDTO.key} does not support ACTION"
            )
        }

        val func: DJIKeyFunc = keyDTO.keyType
            ?: when {
                keyInfo.isCanPerformAction -> DJIKeyFunc.ACTION // Prioritise action
                keyDTO.param == null && keyInfo.isCanGet -> DJIKeyFunc.GET // If no param given - prioritise get
                keyInfo.isCanSet -> DJIKeyFunc.SET
                keyInfo.isCanGet -> DJIKeyFunc.GET
                else -> throw UnsupportedOperationException("key ${keyDTO.key} does not support any known operation")
            }

        // Execute (keyItem get/set/action owns it's parameter Typing)
        return when (func) {
            DJIKeyFunc.GET -> keyItem.get()
            DJIKeyFunc.SET -> keyItem.set(keyDTO.param)
            DJIKeyFunc.ACTION -> keyItem.action(keyDTO.param)
        }
    }
}