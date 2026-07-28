package com.kcg.dr.utils

import dji.v5.common.error.IDJIError
import dji.v5.lib.codec.util.DJIRuntimeException

class DJIErrorException(val error: IDJIError, throwable: Throwable? = null) :
    DJIRuntimeException(
        "${error.errorType()}: " +
                "${error.errorCode()},${error.innerCode()} " +
                "${error.description() ?: ""} ${error.hint() ?: ""}",
        throwable
    )