package com.screentranslate.he

import android.content.Context

/** Which writing system the OCR should look for. */
enum class Script { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

object Prefs {
    private const val FILE = "settings"
    private const val KEY_SCRIPT = "script"

    fun getScript(context: Context): Script {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SCRIPT, Script.LATIN.name)
        return Script.entries.firstOrNull { it.name == name } ?: Script.LATIN
    }

    fun setScript(context: Context, script: Script) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCRIPT, script.name).apply()
    }
}
