package com.q09009.studytablet.data

import android.content.Context
import android.net.Uri

/** Small persisted settings: AI model, workspace folder and open tabs. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    val modelChosen: Boolean get() = prefs.contains("model")

    var workspace: Uri?
        get() = prefs.getString("workspace", null)?.let(Uri::parse)
        set(v) = prefs.edit().putString("workspace", v?.toString()).apply()

    var editor: String?
        get() = prefs.getString("editor", null)
        set(v) = prefs.edit().putString("editor", v).apply()

    var split: Float
        get() = prefs.getFloat("split", 0.5f).coerceIn(0.2f, 0.8f)
        set(v) = prefs.edit().putFloat("split", v).apply()

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit().putString("theme", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gpt-5"
    }
}
