package com.kcg.dr.utils

import dji.v5.common.error.IDJIError
import dji.v5.lib.codec.util.DJIRuntimeException

class DJIErrorException(val error: IDJIError) : DJIRuntimeException(error.description())