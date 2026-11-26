package com.q09009.studytablet.ai

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfSummarizer {
    private const val CHUNK_CHARS = 60_000

    private const val INSTRUCTIONS = """당신은 대학 강의 자료를 정리해주는 학습 도우미입니다.
주어진 PDF 텍스트를 공부하기 좋은 한국어 마크다운 요약본으로 만드세요.
규칙:
- 제목(#)과 섹션(##, ###)으로 구조화
- 핵심 개념은 **굵게**, 정의/공식은 목록이나 표로 정리
- 수식은 원문 표기를 최대한 유지 (인라인 코드 사용 가능)
- 원문에 페이지 구분([p.N])이 있으면 관련 섹션에 (p.N) 형태로 출처 표시
- 개념의 난이도에 따라 분량을 조절:
  - 쉽거나 단순한 내용(정의 나열, 용어, 사실)은 짧게 요약
  - 어렵거나 헷갈리기 쉬운 개념(유도 과정, 여러 단계 추론, 직관이 잘 안 오는 개념, 원문이 설명 없이 넘어가는 부분)은
    요약 바로 아래에 "> **💡 자세히**" 인용 블록으로 풀어서 설명: 왜 그런지(직관), 단계별 설명, 간단한 예시나 비유,
    자주 하는 실수. 원문에 없는 보충 설명은 일반적으로 알려진 내용만 사용
- 마지막에 "## 핵심 정리" (5~10개 bullet)와 "## 예상 질문" (3~5개) 섹션 추가
- 원문에 없는 내용을 지어내지 말 것
- 마크다운 본문만 출력 (코드블록으로 감싸지 말 것)"""

    private const val MERGE_INSTRUCTIONS = """당신은 학습 도우미입니다. 하나의 강의 자료를 여러 부분으로 나눠 요약한 마크다운들이 주어집니다.
중복을 제거하고 하나의 일관된 한국어 마크다운 요약본으로 합치세요.
구조(#, ##, ###), 페이지 출처 (p.N), "> **💡 자세히**" 설명 블록(줄이지 말고 유지), 마지막의 "## 핵심 정리"와 "## 예상 질문" 섹션을 유지하세요.
마크다운 본문만 출력하세요."""

    suspend fun extractText(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                (1..doc.numberOfPages).map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.getText(doc).trim()
                }
            }
        }
    }

    /**
     * Produces a markdown summary. [onProgress] gets status text, [onPartial] the markdown so far.
     */
    suspend fun summarize(
        context: Context,
        uri: Uri,
        client: CodexClient,
        model: String,
        onProgress: (String) -> Unit,
        onPartial: (String) -> Unit,
    ): String {
        onProgress("PDF 텍스트 추출 중…")
        val pages = extractText(context, uri)
        if (pages.all { it.isBlank() }) {
            throw IllegalStateException("PDF에서 텍스트를 찾을 수 없습니다 (스캔 이미지 PDF일 수 있습니다).")
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        pages.forEachIndexed { i, text ->
            if (text.isBlank()) return@forEachIndexed
            val block = "[p.${i + 1}]\n$text\n\n"
            if (current.length + block.length > CHUNK_CHARS && current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
            current.append(block.take(CHUNK_CHARS))
        }
        if (current.isNotEmpty()) chunks += current.toString()

        if (chunks.size == 1) {
            onProgress("AI 요약 생성 중…")
            val sb = StringBuilder()
            return client.complete(model, INSTRUCTIONS, chunks[0]) {
                sb.append(it); onPartial(sb.toString())
            }
        }

        val partials = chunks.mapIndexed { i, chunk ->
            onProgress("부분 요약 ${i + 1}/${chunks.size} 생성 중…")
            val sb = StringBuilder()
            client.complete(model, INSTRUCTIONS, chunk) {
                sb.append(it); onPartial(sb.toString())
            }
        }
        onProgress("요약 합치는 중…")
        val merged = partials.mapIndexed { i, s -> "--- 부분 ${i + 1} ---\n$s" }.joinToString("\n\n")
        val sb = StringBuilder()
        return client.complete(model, MERGE_INSTRUCTIONS, merged) {
            sb.append(it); onPartial(sb.toString())
        }
    }
}
