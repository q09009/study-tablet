package com.q09009.studytablet.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class FileKind { DIR, PDF, TEXT, AUDIO, OTHER }

data class FsNode(
    val uri: Uri,
    val name: String,
    val isDir: Boolean,
    val mime: String,
    val size: Long,
    val modified: Long,
    val parent: Uri?,
) {
    val kind: FileKind
        get() {
            if (isDir) return FileKind.DIR
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" || mime == "application/pdf" -> FileKind.PDF
                ext in TEXT_EXT -> FileKind.TEXT
                ext in AUDIO_EXT || mime.startsWith("audio/") -> FileKind.AUDIO
                else -> FileKind.OTHER
            }
        }

    companion object {
        private val TEXT_EXT = setOf("md", "markdown", "txt")
        private val AUDIO_EXT = setOf("m4a", "mp3", "wav", "aac", "ogg", "3gp", "opus", "flac")
    }
}

/** File operations on the user-chosen workspace folder (Storage Access Framework tree). */
class Workspace(context: Context) {
    private val cr = context.applicationContext.contentResolver

    fun takePermission(tree: Uri) {
        cr.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun hasPermission(tree: Uri): Boolean =
        cr.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    suspend fun root(tree: Uri): FsNode = withContext(Dispatchers.IO) {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        stat(doc, null) ?: throw IOException("폴더에 접근할 수 없습니다.")
    }

    private fun stat(uri: Uri, parent: Uri?): FsNode? =
        cr.query(uri, COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else node(c, uri, parent)
        }

    private fun node(c: android.database.Cursor, uri: Uri, parent: Uri?): FsNode {
        val mime = c.getString(2).orEmpty()
        return FsNode(
            uri = uri,
            name = c.getString(1).orEmpty(),
            isDir = mime == Document.MIME_TYPE_DIR,
            mime = mime,
            size = if (c.isNull(3)) 0 else c.getLong(3),
            modified = if (c.isNull(4)) 0 else c.getLong(4),
            parent = parent,
        )
    }

    suspend fun list(dir: Uri): List<FsNode> = withContext(Dispatchers.IO) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        val out = mutableListOf<FsNode>()
        cr.query(children, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(dir, c.getString(0))
                val n = node(c, uri, dir)
                if (!n.name.startsWith(".")) out += n
            }
        }
        out.sortedWith(compareBy<FsNode>({ !it.isDir }, { it.name.lowercase() }))
    }

    suspend fun createDir(parent: Uri, name: String): Uri = create(parent, Document.MIME_TYPE_DIR, name)

    /** Creates an empty file. Uses a generic MIME type so the provider keeps the exact extension. */
    suspend fun createFile(parent: Uri, name: String, mime: String = "application/octet-stream"): Uri =
        create(parent, mime, name)

    private suspend fun create(parent: Uri, mime: String, name: String): Uri = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(cr, parent, mime, name) ?: throw IOException("만들 수 없습니다: $name")
    }

    suspend fun rename(uri: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        DocumentsContract.renameDocument(cr, uri, name) ?: uri
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
        if (!DocumentsContract.deleteDocument(cr, uri)) throw IOException("삭제할 수 없습니다.")
    }

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        cr.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IOException("파일을 읽을 수 없습니다.")
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        (cr.openOutputStream(uri, "wt") ?: throw IOException("파일에 쓸 수 없습니다.")).use {
            it.write(text.encodeToByteArray())
        }
    }

    suspend fun find(dir: Uri, name: String): FsNode? = list(dir).firstOrNull { it.name == name }

    /** Moves [node] into [target]; falls back to copy + delete when the provider can't move. Returns the new uri. */
    suspend fun move(node: FsNode, target: Uri): Uri = withContext(Dispatchers.IO) {
        val parent = node.parent ?: throw IOException("이동할 수 없습니다: ${node.name}")
        runCatching { DocumentsContract.moveDocument(cr, node.uri, parent, target) }.getOrNull()?.let { return@withContext it }
        if (node.isDir) throw IOException("폴더를 이동할 수 없습니다: ${node.name}")
        val dst = DocumentsContract.createDocument(cr, target, node.mime.ifEmpty { "application/octet-stream" }, node.name)
            ?: throw IOException("만들 수 없습니다: ${node.name}")
        cr.openInputStream(node.uri)!!.use { input -> cr.openOutputStream(dst, "w")!!.use { input.copyTo(it) } }
        DocumentsContract.deleteDocument(cr, node.uri)
        dst
    }

    fun openOutput(uri: Uri) = cr.openOutputStream(uri, "w") ?: throw IOException("파일에 쓸 수 없습니다.")

    /** Copies a content uri (e.g. a picked PDF) into [dir]. */
    suspend fun importUri(dir: Uri, src: Uri, name: String, mime: String): Uri {
        val dst = createFile(dir, name, mime)
        withContext(Dispatchers.IO) {
            cr.openInputStream(src)!!.use { input ->
                cr.openOutputStream(dst, "w")!!.use { input.copyTo(it) }
            }
        }
        return dst
    }

    fun importFileBlocking(dir: Uri, src: File, name: String, mime: String): Uri {
        val dst = DocumentsContract.createDocument(cr, dir, mime, name) ?: throw IOException("만들 수 없습니다: $name")
        src.inputStream().use { input -> cr.openOutputStream(dst, "w")!!.use { input.copyTo(it) } }
        return dst
    }

    fun displayName(uri: Uri): String {
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
