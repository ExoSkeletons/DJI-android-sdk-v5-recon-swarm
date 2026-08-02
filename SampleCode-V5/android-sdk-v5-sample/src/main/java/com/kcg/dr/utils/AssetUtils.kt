package com.kcg.dr.utils

import android.content.Context
import android.util.Log
import java.io.File

object AssetUtils {
    fun Context.getAssetOrExtract(fileName: String, forceCopy: Boolean = false): File {
        Log.d("Asset", "getFileOrCopyFromAssets: $fileName")
        val output = File(this.filesDir, fileName)
        if (!forceCopy && output.exists()) return output

        val assetFile = this.assets.open(fileName)
        Log.d("Asset", "copying from $fileName asset to ${output.absolutePath}...")
        output.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        output.createNewFile()
        assetFile.use { input ->
            output.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return output
    }
}