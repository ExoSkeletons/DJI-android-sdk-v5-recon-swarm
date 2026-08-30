@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto

import com.kcg.dr.api.dto.actions.Action
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable


@Serializable
data class FlyRequest(val actions: List<Action>)