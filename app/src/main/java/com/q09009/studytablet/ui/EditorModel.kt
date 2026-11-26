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

/** Which pane a tab lives in. Each kind gets its own pane, ordered left to right as declared. */
enum class PaneKind(val label: String) { PDF("PDF"), DOC("문서"), AUDIO("녹음"), CHAT("AI 질문") }

val TabContent.kind: PaneKind
    get() = when (this) {
        is TabContent.Pdf -> PaneKind.PDF
        is TabContent.Text, is TabContent.Summary -> PaneKind.DOC
        is TabContent.Audio, TabContent.Recorder -> PaneKind.AUDIO
        TabContent.Chat -> PaneKind.CHAT
    }

data class Pane(
    val kind: PaneKind,
    val tabs: List<TabContent> = emptyList(),
    val active: String? = null,
    val id: String = kind.name,
) {
    val activeTab: TabContent? get() = tabs.firstOrNull { it.key == active } ?: tabs.lastOrNull()
}

const val MAX_PANES = 4

data class EditorState(val panes: List<Pane> = emptyList(), val focused: Int = 0) {
    val focusedTab: TabContent? get() = panes.getOrNull(focused)?.activeTab

    fun findPane(key: String): Int = panes.indexOfFirst { p -> p.tabs.any { it.key == key } }

    private fun newId(kind: PaneKind): String {
        if (panes.none { it.id == kind.name }) return kind.name
        var n = 2
        while (panes.any { it.id == "${kind.name}-$n" }) n++
        return "${kind.name}-$n"
    }

    /** Where a new pane of [kind] goes: after the last pane of an earlier-or-equal kind. */
    private fun slotFor(kind: PaneKind): Int = panes.indexOfLast { it.kind <= kind } + 1

    private fun insertPane(at: Int, content: TabContent): EditorState {
        val list = panes.toMutableList()
        list.add(at, Pane(content.kind, listOf(content), content.key, newId(content.kind)))
        return EditorState(list, at)
    }

    private fun addTo(pane: Int, content: TabContent): EditorState {
        val list = panes.toMutableList()
        val p = list[pane]
        list[pane] = p.copy(tabs = p.tabs + content, active = content.key)
        return EditorState(list, pane)
    }

    /**
     * Opens [content] in the pane for its kind (creating it if needed). With [toSide], opens it in an extra pane
     * of the same kind next to the existing one instead, so e.g. two PDFs can be compared.
     */
    fun open(content: TabContent, toSide: Boolean = false): EditorState {
        val existing = findPane(content.key)
        if (existing >= 0) {
            if (!toSide || panes[existing].tabs.size == 1) return activate(existing, content.key)
            return splitOff(existing, content.key)
        }
        val kind = content.kind
        val focusedPane = panes.getOrNull(focused)
        val target = if (focusedPane?.kind == kind) focused else panes.indexOfFirst { it.kind == kind }
        if (target < 0) return insertPane(slotFor(kind), content)
        if (toSide && panes.size < MAX_PANES) return insertPane(target + 1, content)
        return addTo(target, content)
    }

    fun activate(pane: Int, key: String): EditorState {
        val list = panes.toMutableList()
        list[pane] = list[pane].copy(active = key)
        return EditorState(list, pane)
    }

    fun close(pane: Int, key: String): EditorState {
        val p = panes.getOrNull(pane) ?: return this
        val idx = p.tabs.indexOfFirst { it.key == key }
        if (idx < 0) return this
        val tabs = p.tabs.toMutableList().apply { removeAt(idx) }
        val list = panes.toMutableList()
        if (tabs.isEmpty()) {
            list.removeAt(pane)
            val f = if (focused > pane) focused - 1 else focused
            return EditorState(list, f.coerceIn(0, maxOf(0, list.lastIndex)))
        }
        val active = if (p.active == key) tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.key else p.active
        list[pane] = p.copy(tabs = tabs, active = active)
        return EditorState(list, focused.coerceIn(0, list.lastIndex))
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
        val list = base.panes.toMutableList()
        val p = list[pi]
        list[pi] = p.copy(
            tabs = p.tabs.map { if (it.key == key) content else it },
            active = if (p.active == key) content.key else p.active,
        )
        return EditorState(list, base.focused)
    }

    /** Another pane of the same kind as [pane], if any (tabs can be merged back into it). */
    fun sibling(pane: Int): Int {
        val kind = panes.getOrNull(pane)?.kind ?: return -1
        return panes.indices.firstOrNull { it != pane && panes[it].kind == kind } ?: -1
    }

    fun canSplit(pane: Int) = (panes.getOrNull(pane)?.tabs?.size ?: 0) > 1 && panes.size < MAX_PANES

    private fun splitOff(pane: Int, key: String): EditorState {
        val tab = panes[pane].tabs.first { it.key == key }
        val base = close(pane, key)
        val list = base.panes.toMutableList()
        list.add(pane + 1, Pane(tab.kind, listOf(tab), tab.key, base.newId(tab.kind)))
        return EditorState(list, pane + 1)
    }

    /** Moves a tab into its same-kind sibling pane, or splits it into a new pane when there is none. */
    fun moveToOther(pane: Int, key: String): EditorState {
        val tab = panes.getOrNull(pane)?.tabs?.firstOrNull { it.key == key } ?: return this
        val sib = sibling(pane)
        if (sib < 0) return if (canSplit(pane)) splitOff(pane, key) else this
        val sibId = panes[sib].id
        val base = close(pane, key)
        return base.addTo(base.panes.indexOfFirst { it.id == sibId }, tab)
    }

    fun toJson(): String {
        val arr = JSONArray()
        panes.forEach { p ->
            val tabs = JSONArray()
            p.tabs.mapNotNull(::tabJson).forEach(tabs::put)
            arr.put(JSONObject().put("tabs", tabs).put("active", p.active ?: "").put("kind", p.kind.name).put("id", p.id))
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
            val raw = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val tabsArr = p.getJSONArray("tabs")
                val tabs = (0 until tabsArr.length()).mapNotNull { parseTab(tabsArr.getJSONObject(it)) }
                Triple(p, tabs, p.optString("active").takeIf { it.isNotEmpty() })
            }.filter { it.second.isNotEmpty() }
            val kinds = raw.map { (p, tabs, _) -> PaneKind.entries.firstOrNull { it.name == p.optString("kind") } to tabs }
            if (kinds.all { (k, tabs) -> k != null && tabs.all { it.kind == k } }) {
                val panes = raw.mapIndexed { i, (p, tabs, active) ->
                    val k = kinds[i].first!!
                    Pane(k, tabs, active, p.optString("id").ifEmpty { k.name })
                }.distinctBy { it.id }
                EditorState(panes, o.optInt("focused").coerceIn(0, maxOf(0, panes.lastIndex)))
            } else {
                // Older layout (mixed panes): regroup tabs by kind.
                raw.flatMap { it.second }.fold(EditorState()) { s, t -> s.open(t) }.copy(focused = 0)
            }
        }.getOrDefault(EditorState())
    }
}
