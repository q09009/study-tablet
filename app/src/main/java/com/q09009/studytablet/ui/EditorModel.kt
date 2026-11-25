package com.q09009.studytablet.ui

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Something that can be shown in an editor tab. [key] identifies it across panes. */
sealed interface TabContent {
    val key: String
    val title: String

    data class Pdf(val uri: Uri, val name: String, val parent: Uri?) : TabContent {
        override val key get() = "pdf:$uri"
        override val title get() = name
    }

    data class Text(val uri: Uri, val name: String, val parent: Uri?) : TabContent {
        override val key get() = "text:$uri"
        override val title get() = name
    }

    data class Audio(val uri: Uri, val name: String) : TabContent {
        override val key get() = "audio:$uri"
        override val title get() = name
    }

    /** A summary being generated for a PDF; replaced by a [Text] tab when saved. */
    data class Summary(val pdf: Pdf) : TabContent {
        override val key get() = "summary:${pdf.uri}"
        override val title get() = "${pdf.name.substringBeforeLast('.')} 요약"
    }

    data object Recorder : TabContent {
        override val key get() = "recorder"
        override val title get() = "녹음"
    }

    data object Chat : TabContent {
        override val key get() = "chat"
        override val title get() = "AI 질문"
    }
}

data class Pane(val tabs: List<TabContent> = emptyList(), val active: String? = null) {
    val activeTab: TabContent? get() = tabs.firstOrNull { it.key == active } ?: tabs.lastOrNull()
}

data class EditorState(val panes: List<Pane> = listOf(Pane()), val focused: Int = 0) {
    val focusedTab: TabContent? get() = panes.getOrNull(focused)?.activeTab

    fun findPane(key: String): Int = panes.indexOfFirst { p -> p.tabs.any { it.key == key } }

    fun open(content: TabContent, toSide: Boolean = false): EditorState {
        val existing = findPane(content.key)
        if (existing >= 0 && !toSide) return activate(existing, content.key)
        val base = if (existing >= 0) close(existing, content.key) else this
        val target = when {
            !toSide -> base.focused.coerceIn(0, base.panes.lastIndex)
            base.panes.size == 1 -> 1
            else -> 1 - base.focused.coerceIn(0, 1)
        }
        val panes = base.panes.toMutableList()
        if (target == panes.size) panes += Pane()
        val p = panes[target]
        panes[target] = Pane(p.tabs + content, content.key)
        return EditorState(panes, target)
    }

    fun activate(pane: Int, key: String): EditorState {
        val panes = panes.toMutableList()
        panes[pane] = panes[pane].copy(active = key)
        return EditorState(panes, pane)
    }

    fun close(pane: Int, key: String): EditorState {
        val p = panes.getOrNull(pane) ?: return this
        val idx = p.tabs.indexOfFirst { it.key == key }
        if (idx < 0) return this
        val tabs = p.tabs.toMutableList().apply { removeAt(idx) }
        val active = if (p.active == key) tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.key else p.active
        val panes = panes.toMutableList()
        if (tabs.isEmpty() && panes.size > 1) {
            panes.removeAt(pane)
            return EditorState(panes, 0)
        }
        panes[pane] = Pane(tabs, active)
        return EditorState(panes, focused.coerceIn(0, panes.lastIndex))
    }

    fun closeKey(key: String): EditorState {
        var s = this
        while (true) {
            val i = s.findPane(key)
            if (i < 0) return s
            s = s.close(i, key)
        }
    }

    fun replace(key: String, content: TabContent): EditorState {
        val i = findPane(key)
        if (i < 0) return this
        val other = findPane(content.key)
        val base = if (other >= 0 && content.key != key) close(other, content.key) else this
        val pi = base.findPane(key)
        val panes = base.panes.toMutableList()
        val p = panes[pi]
        panes[pi] = Pane(
            p.tabs.map { if (it.key == key) content else it },
            if (p.active == key) content.key else p.active,
        )
        return EditorState(panes, base.focused)
    }

    fun moveToOther(pane: Int, key: String): EditorState {
        val tab = panes.getOrNull(pane)?.tabs?.firstOrNull { it.key == key } ?: return this
        return copy(focused = pane).open(tab, toSide = true)
    }

    fun toJson(): String {
        val arr = JSONArray()
        panes.forEach { p ->
            val tabs = JSONArray()
            p.tabs.mapNotNull(::tabJson).forEach(tabs::put)
            arr.put(JSONObject().put("tabs", tabs).put("active", p.active ?: ""))
        }
        return JSONObject().put("panes", arr).put("focused", focused).toString()
    }

    companion object {
        private fun tabJson(t: TabContent): JSONObject? = when (t) {
            is TabContent.Pdf -> JSONObject().put("t", "pdf").put("uri", "${t.uri}").put("name", t.name)
                .put("parent", t.parent?.toString() ?: "")
            is TabContent.Text -> JSONObject().put("t", "text").put("uri", "${t.uri}").put("name", t.name)
                .put("parent", t.parent?.toString() ?: "")
            is TabContent.Audio -> JSONObject().put("t", "audio").put("uri", "${t.uri}").put("name", t.name)
            is TabContent.Recorder -> JSONObject().put("t", "rec")
            is TabContent.Chat -> JSONObject().put("t", "chat")
            is TabContent.Summary -> null
        }

        private fun parseTab(o: JSONObject): TabContent? {
            val uri = Uri.parse(o.optString("uri"))
            val parent = o.optString("parent").takeIf { it.isNotEmpty() }?.let(Uri::parse)
            return when (o.optString("t")) {
                "pdf" -> TabContent.Pdf(uri, o.optString("name"), parent)
                "text" -> TabContent.Text(uri, o.optString("name"), parent)
                "audio" -> TabContent.Audio(uri, o.optString("name"))
                "rec" -> TabContent.Recorder
                "chat" -> TabContent.Chat
                else -> null
            }
        }

        fun fromJson(json: String?): EditorState = runCatching {
            val o = JSONObject(json!!)
            val arr = o.getJSONArray("panes")
            val panes = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val tabsArr = p.getJSONArray("tabs")
                val tabs = (0 until tabsArr.length()).mapNotNull { parseTab(tabsArr.getJSONObject(it)) }
                Pane(tabs, p.optString("active").takeIf { it.isNotEmpty() })
            }.filter { it.tabs.isNotEmpty() }.ifEmpty { listOf(Pane()) }
            EditorState(panes, o.optInt("focused").coerceIn(0, panes.lastIndex))
        }.getOrDefault(EditorState())
    }
}
