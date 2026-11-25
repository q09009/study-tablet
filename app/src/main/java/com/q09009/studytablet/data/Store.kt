package com.q09009.studytablet.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RecentDoc(val uri: Uri, val name: String, val openedAt: Long)

/** Simple persistence for recent documents, cached summaries and settings. */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    fun recentDocs(): List<RecentDoc> {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentDoc(Uri.parse(o.getString("uri")), o.getString("name"), o.getLong("at"))
        }
    }

    fun addRecent(uri: Uri, name: String) {
        val list = recentDocs().filterNot { it.uri == uri }.toMutableList()
        list.add(0, RecentDoc(uri, name, System.currentTimeMillis()))
        val arr = JSONArray()
        list.take(30).forEach {
            arr.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.openedAt))
        }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    fun removeRecent(uri: Uri) {
        val arr = JSONArray()
        recentDocs().filterNot { it.uri == uri }.forEach {
            arr.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.openedAt))
        }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    private fun docDir(uri: Uri): File =
        File(appContext.filesDir, "docs/${docKey(uri)}").apply { mkdirs() }

    fun loadSummary(uri: Uri): String? =
        File(docDir(uri), "summary.md").takeIf { it.exists() }?.readText()

    fun saveSummary(uri: Uri, markdown: String) {
        File(docDir(uri), "summary.md").writeText(markdown)
    }

    fun recordingsDir(uri: Uri): File = File(docDir(uri), "recordings").apply { mkdirs() }

    companion object {
        const val DEFAULT_MODEL = "gpt-5-mini"

        fun docKey(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
