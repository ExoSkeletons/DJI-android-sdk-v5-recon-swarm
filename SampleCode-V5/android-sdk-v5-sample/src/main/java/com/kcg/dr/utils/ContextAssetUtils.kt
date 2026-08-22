package com.kcg.dr.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

fun Context.getExecutableFromLibs(fileName: String): File {
    val libDir = File(this.applicationInfo.nativeLibraryDir)
    val candidates = listOf(
        File(libDir, "lib$fileName.so"),
        File(libDir, fileName),
        File(libDir, "lib$fileName"),
        File(libDir, "$fileName.so"),
    )
    for (file in candidates)
        if (file.exists())
            return file
    throw IOException(
        "Executable lib file $fileName is missing from native library directory ${libDir.absolutePath}.\n" +
                "Checked: ${candidates.joinToString(", ") { it.absolutePath }}\n" +
                "Did you forget to put it in jniLibs?"
    )
}

@Deprecated(
    "W^X violation on Android 10+",
    ReplaceWith("getExecutibleFromLibs(context, fileName)")
)
fun Context.getExecutableFromAssets(fileName: String): File {
    val file = this.getAssetOrExtract(fileName, false)
    Log.d("Asset", "making $fileName executable...")
    // Note: chmod exec perm set fails on Android 10+
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Log.d("Asset", "making $fileName executable...")
        ProcessBuilder("chmod", "755", file.absolutePath)
            .start()
            .waitFor()
    }
    return file
}