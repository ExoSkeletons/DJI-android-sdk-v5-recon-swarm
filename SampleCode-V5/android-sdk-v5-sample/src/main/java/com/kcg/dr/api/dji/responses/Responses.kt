package com.kcg.dr.api.dji.responses

import com.kcg.dr.api.responses.nok
import com.kcg.dr.djiutils.DJIErrorException
import com.kcg.dr.djiutils.toJson
import dji.v5.common.error.IDJIError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject


fun djiErrorResponse(
    e: DJIErrorException,
    builderAction: JsonObjectBuilder.(IDJIError) -> Unit = {}
): JsonObject = nok {
    put("djiError", buildJsonObject {
        e.error.toJson() + buildJsonObject { builderAction(e.error) }
    })
}