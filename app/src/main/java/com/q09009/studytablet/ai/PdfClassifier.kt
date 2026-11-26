package com.q09009.studytablet.ai

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Asks the model which subject (course) each PDF belongs to, based on its name and first pages. */
object PdfClassifier {
    private const val EXCERPT_PAGES = 3
    private const val EXCERPT_CHARS = 1500
    private const val BATCH = 10

    private const val INSTRUCTIONS = """당신은 대학생의 공부 자료를 과목별 폴더로 정리해주는 도우미입니다.
각 PDF의 파일 이름과 앞부분 텍스트를 보고 어떤 과목(강의)의 자료인지 판단하세요.
규칙:
- 기존 과목 폴더 목록에 맞는 과목이 있으면 그 이름을 글자 그대로 사용
- 없으면 새 과목 이름을 짧게 만들기 (예: "운영체제", "자료구조", "선형대수"). 한국어 과목명을 우선, 원문이 영어 과목이면 흔히 쓰는 한국어 이름
- 같은 과목인 자료들은 반드시 같은 이름으로
- 판단할 수 없으면 "기타"
- 출력은 JSON 배열만: [{"id": 0, "subject": "운영체제"}, ...]  (설명, 코드블록 없이)"""

    suspend fun excerpt(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                PDDocument.load(input).use { doc ->
                    val s = PDFTextStripper()
                    s.startPage = 1
                    s.endPage = minOf(EXCERPT_PAGES, doc.numberOfPages)
                    s.getText(doc).replace(Regex("\\s+"), " ").trim().take(EXCERPT_CHARS)
                }
            }
        }.getOrDefault("")
    }

    /**
     * Returns a subject for each of [files] (name to uri), in order. [subjects] are existing folder names.
     */
    suspend fun classify(
        context: Context,
        client: CodexClient,
        model: String,
        files: List<Pair<String, Uri>>,
        subjects: List<String>,
        onProgress: (String) -> Unit,
    ): List<String> {
        val result = MutableList(files.size) { "기타" }
        val known = subjects.toMutableList()
        files.indices.chunked(BATCH).forEach { batch ->
            val docs = batch.map { i ->
                onProgress("PDF 읽는 중… (${i + 1}/${files.size})")
                i to excerpt(context, files[i].second)
            }
            onProgress("AI가 과목 분류 중… (${batch.last() + 1}/${files.size})")
            val input = buildString {
                append("기존 과목 폴더: ")
                append(if (known.isEmpty()) "(없음)" else known.joinToString(", ") { "\"$it\"" })
                append("\n\n")
                docs.forEachIndexed { j, (i, text) ->
                    append("[id ").append(j).append("] 파일: ").append(files[i].first).append('\n')
                    append(text.ifBlank { "(텍스트 없음)" }).append("\n\n")
                }
            }
            val reply = client.complete(model, INSTRUCTIONS, input)
            parse(reply).forEach { (j, subject) ->
                val i = docs.getOrNull(j)?.first ?: return@forEach
                val clean = sanitize(subject)
                result[i] = clean
                if (clean !in known) known += clean
            }
        }
        return result
    }

    private fun parse(reply: String): List<Pair<Int, String>> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val arr = JSONArray(reply.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { k ->
                val o: JSONObject = arr.optJSONObject(k) ?: return@mapNotNull null
                o.optInt("id", -1).takeIf { it >= 0 }?.let { it to o.optString("subject", "기타") }
            }
        }.getOrDefault(emptyList())
    }

    /** Folder-safe subject name. */
    fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { "기타" }
}
