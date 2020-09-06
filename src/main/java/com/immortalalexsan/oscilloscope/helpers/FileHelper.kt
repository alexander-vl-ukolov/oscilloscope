package com.immortalalexsan.oscilloscope.helpers

import android.content.Context

internal object FileHelper {

    fun readTextFromRaw(context: Context, rawId: Int): String {
        val stringBuilder = StringBuilder()
        context.resources.openRawResource(rawId).bufferedReader().forEachLine {
            stringBuilder.append(it).append("\r\n")
        }
        return stringBuilder.toString()
    }
}
