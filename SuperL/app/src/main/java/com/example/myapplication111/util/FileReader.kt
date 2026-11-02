package com.example.myapplication111.util

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 读取用户选择的 txt / pdf 文件为纯文本，并做版面坐标分析与空白清洗。 */
object FileReader {

    fun readFileContent(context: Context, uri: Uri): String? {
        val mimeType = context.contentResolver.getType(uri)
        return if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf")) {
            readPdfFromUri(context, uri)
        } else {
            readTextFromUri(context, uri)?.let { formatPlainText(it) }
        }
    }

    private fun readTextFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readPdfFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // PDDocument 实现 Closeable：用 use 保证解析失败时也不会泄漏句柄
                PDDocument.load(inputStream).use { document ->
                    val stripper = LayoutAwarePdfStripper()
                    stripper.getText(document)
                    val structured = stripper.buildStructuredText()
                    if (structured.isNotBlank()) {
                        structured
                    } else {
                        // 兜底：若极特殊 PDF 未触发坐标回调，回退到纯文本语义修复
                        val fallbackStripper = PDFTextStripper().apply { sortByPosition = true }
                        formatExtractedPdfText(fallbackStripper.getText(document))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 基于物理坐标与字号行距的 PDF 提取器：
     *
     * 为什么不用 PDFTextStripper 自带的 dropThreshold？
     * 因为 PDFTextStripper 内部比较的是 `yGap (相邻行底到顶的距离) > dropThreshold * maxHeightForLine`。
     * 对于常见的 11.5pt 正文，段内行距 yGap 约 5.8pt（0.5x），段间距离 yGap 约 13.8pt（1.2x）；
     * 但 PDFTextStripper 在计算 maxHeightForLine 时若某一行仅包含无上下延展的短字母，其测量高度偏小，
     * 会导致段内普通折行也误触发 isParagraphSeparation，从而在每行之间平白无故插入空行（如 wanted to meet \n\n me）。
     *
     * 本提取器直接采集每一行的真实基线坐标 Y、字号 pt 与页码：
     * 1. 自动统计整篇文档的基准段内行距（取同页相邻行间距的 25% 分位数）；
     * 2. 当相邻行间距明显大于基准段内行距（> 1.22 倍）或字号发生跳变（标题与正文切换）时，才判定为真正的段落边界（\n\n）；
     * 3. 同一段落内的所有排版折行直接无缝拼接为连贯的整段，彻底杜绝句中被插入换行或空行。
     */
    private class LayoutAwarePdfStripper : PDFTextStripper() {

        data class PdfLine(
            val pageIndex: Int,
            val text: String,
            val y: Float,
            val fontSize: Float,
            val x: Float
        )

        private val extractedLines = mutableListOf<PdfLine>()
        private val currentLineBuffer = StringBuilder()
        private var currentLineY = -1f
        private var currentLineFontSize = 0f
        private var currentLineX = -1f
        private var currentPageIndex = 0

        init {
            sortByPosition = true
        }

        override fun startPage(page: PDPage?) {
            super.startPage(page)
            flushCurrentLine()
            currentPageIndex++
        }

        override fun endPage(page: PDPage?) {
            flushCurrentLine()
            super.endPage(page)
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (!text.isNullOrEmpty() && !textPositions.isNullOrEmpty()) {
                val first = textPositions.first()
                if (currentLineBuffer.isEmpty()) {
                    currentLineY = first.yDirAdj
                    currentLineX = first.xDirAdj
                    currentLineFontSize = first.fontSizeInPt
                } else {
                    for (tp in textPositions) {
                        if (tp.fontSizeInPt > currentLineFontSize) {
                            currentLineFontSize = tp.fontSizeInPt
                        }
                    }
                }
                currentLineBuffer.append(text)
            }
            super.writeString(text, textPositions)
        }

        override fun writeLineSeparator() {
            flushCurrentLine()
            super.writeLineSeparator()
        }

        override fun writeWordSeparator() {
            if (currentLineBuffer.isNotEmpty() && !currentLineBuffer.endsWith(" ")) {
                currentLineBuffer.append(" ")
            }
            super.writeWordSeparator()
        }

        private fun flushCurrentLine() {
            val raw = currentLineBuffer.toString().trim()
            if (raw.isNotEmpty()) {
                extractedLines.add(
                    PdfLine(
                        pageIndex = currentPageIndex,
                        text = raw,
                        y = currentLineY,
                        fontSize = if (currentLineFontSize > 0f) currentLineFontSize else 11.5f,
                        x = currentLineX
                    )
                )
            }
            currentLineBuffer.clear()
            currentLineY = -1f
            currentLineFontSize = 0f
            currentLineX = -1f
        }

        fun buildStructuredText(): String {
            flushCurrentLine()
            if (extractedLines.isEmpty()) return ""

            // 1. 统计同页内相邻行的垂直基线间距 dy，求出基准段内行距
            val dys = mutableListOf<Float>()
            for (i in 1 until extractedLines.size) {
                val curr = extractedLines[i]
                val prev = extractedLines[i - 1]
                if (curr.pageIndex == prev.pageIndex) {
                    val dy = curr.y - prev.y
                    if (dy > 2.0f && dy < 100.0f) {
                        dys.add(dy)
                    }
                }
            }

            dys.sort()
            // 取 25% 分位数作为段内标准行距（例如 Day 01.pdf 中段内恒为 17.3pt，段间为 25.3pt）
            val baseLineSpacing = if (dys.isNotEmpty()) {
                dys[dys.size / 4]
            } else {
                17.0f
            }
            val paraGapThreshold = baseLineSpacing * 1.22f

            // 2. 按物理行距、字号变化与列表特征聚合成自然段落
            val paragraphs = mutableListOf<String>()
            val currentPara = StringBuilder()

            for (i in extractedLines.indices) {
                val line = extractedLines[i]
                val lineText = line.text.trim()
                if (lineText.isEmpty()) continue

                if (currentPara.isEmpty()) {
                    currentPara.append(lineText)
                    continue
                }

                val prev = extractedLines[i - 1]
                val isNewParagraph = when {
                    // 列表项或章节标题独立成段
                    isListOrHeadingPrefix(lineText) -> true
                    // 同页内：行距明显超过段内行距（> 1.22x）或字号显著变化（标题切换）
                    line.pageIndex == prev.pageIndex -> {
                        val dy = line.y - prev.y
                        val fontDiff = abs(line.fontSize - prev.fontSize)
                        dy > paraGapThreshold || fontDiff > 1.5f
                    }
                    // 跨页：如果上一页末尾句子已完整结束，且新页行不是小写字母续接，则分段
                    else -> {
                        isSentenceComplete(currentPara.toString()) &&
                            !isDefinitelyIncomplete(currentPara.toString(), lineText)
                    }
                }

                if (isNewParagraph) {
                    val cleaned = cleanPdfSpacing(currentPara.toString())
                    if (cleaned.isNotBlank()) paragraphs.add(cleaned)
                    currentPara.clear()
                    currentPara.append(lineText)
                } else {
                    appendLineToParagraph(currentPara, lineText)
                }
            }

            if (currentPara.isNotEmpty()) {
                val cleaned = cleanPdfSpacing(currentPara.toString())
                if (cleaned.isNotBlank()) paragraphs.add(cleaned)
            }

            return paragraphs.joinToString("\n\n")
        }
    }

    /** 将同一段落的下一行平滑拼接到当前段落末尾（处理连字符与中英文空格）。 */
    private fun appendLineToParagraph(para: StringBuilder, nextLine: String) {
        val trimmedNext = nextLine.trim()
        if (trimmedNext.isEmpty()) return
        if (para.isEmpty()) {
            para.append(trimmedNext)
            return
        }

        // 处理英文行末连字符（如 "infor-" + "mation" -> "information"，但保留 "14-" + "to" 这类后跟空格或非粘连词）
        if (para.length >= 2 && para.last() == '-' && para[para.length - 2].isLetter() && trimmedNext.first().isLowerCase()) {
            // 如果连字符前是完整的带连字符复合词前缀（如 "non-"），保留连字符直接拼；否则去掉断词连字符
            val prefixWord = para.takeLastWhile { it.isLetter() || it == '-' }.toString().lowercase()
            if (prefixWord in listOf("non-", "self-", "well-", "co-", "re-", "pre-", "post-", "anti-", "multi-", "inter-", "over-", "under-")) {
                para.append(trimmedNext)
            } else {
                para.deleteCharAt(para.length - 1)
                para.append(trimmedNext)
            }
            return
        }

        val lastChar = para.last()
        val firstChar = trimmedNext.first()
        val isCjkBoundary = isCjkChar(lastChar) && isCjkChar(firstChar)
        if (isCjkBoundary) {
            para.append(trimmedNext)
        } else {
            if (lastChar != ' ') para.append(' ')
            para.append(trimmedNext)
        }
    }

    /** 对导入的纯文本 (.txt) 进行基础规范化，修复异常跨行。 */
    fun formatPlainText(raw: String): String =
        formatExtractedPdfText(raw) ?: raw

    /**
     * 对纯文本或无坐标的提取文本进行语义段落重组与空白清洗：
     * 1. 修复连字符折行（"infor-\nmation" -> "information"）
     * 2. 即便输入文本在句中被错误插入了空行（如 "wanted to meet\n\nme."），也能根据语法特征自动愈合为连续句子
     * 3. 保留真正的自然段落空行（\n\n）
     * 4. 消除标点前多余空格、引号内多余空格、汉字间孤立空格
     */
    fun formatExtractedPdfText(raw: String?): String? {
        if (raw == null) return null
        if (raw.isBlank()) return ""

        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()

        // 1. 修复英文连字符换行（支持跨越单换行或误插的空行）
        val unhyphenated = Regex("([a-zA-Z]{2,})-\\s*\\n+\\s*([a-z]+)").replace(normalized) { m ->
            val prefix = m.groupValues[1]
            val suffix = m.groupValues[2]
            if (prefix.lowercase() in setOf("non", "self", "well", "co", "re", "pre", "post", "anti", "multi", "inter", "over", "under")) {
                "$prefix-$suffix"
            } else {
                prefix + suffix
            }
        }

        // 2. 先按空行切分出初步块，并在每个块内部合并单换行
        val rawBlocks = unhyphenated.split(Regex("\\n\\s*\\n+"))
        val unwrappedBlocks = mutableListOf<String>()

        for (block in rawBlocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val merged = StringBuilder()
            for (line in lines) {
                if (merged.isEmpty()) {
                    merged.append(line)
                    continue
                }
                if (isListOrHeadingPrefix(line)) {
                    unwrappedBlocks.add(cleanPdfSpacing(merged.toString()))
                    merged.clear()
                    merged.append(line)
                } else {
                    appendLineToParagraph(merged, line)
                }
            }
            if (merged.isNotEmpty()) {
                unwrappedBlocks.add(cleanPdfSpacing(merged.toString()))
            }
        }

        // 3. 跨块语法愈合：如果前一个块在句子中间被错误截断（如 "wanted to meet" 和 "me."），将它们合并回同一自然段
        val healedParagraphs = mutableListOf<String>()
        for (block in unwrappedBlocks) {
            if (block.isBlank()) continue
            if (healedParagraphs.isEmpty()) {
                healedParagraphs.add(block)
                continue
            }

            val prev = healedParagraphs.last()
            val shouldMerge = !isListOrHeadingPrefix(block) && (
                isDefinitelyIncomplete(prev, block) ||
                    (!isSentenceComplete(prev) && !isStandaloneHeading(prev, block))
                )

            if (shouldMerge) {
                val sb = StringBuilder(prev)
                appendLineToParagraph(sb, block)
                healedParagraphs[healedParagraphs.lastIndex] = cleanPdfSpacing(sb.toString())
            } else {
                healedParagraphs.add(block)
            }
        }

        return healedParagraphs.joinToString("\n\n")
    }

    /** 判断一行是否是编号列表或显式章节头。 */
    internal fun isListOrHeadingPrefix(line: String): Boolean =
        Regex(
            "^(\\d+[.、)]|[-*•#]|Chapter\\s+\\d+|第[0-9一二三四五六七八九十百]+[章节回卷])(\\s+|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(line.trim())

    /** 判断文本是否以完整句末标点结束。 */
    internal fun isSentenceComplete(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (!Regex("[。！？!?；;….\"”’』」》）)\\]]$").containsMatchIn(t)) return false
        // 若以引号/括号结尾，检查引号内侧是否有句末标点
        val last = t.last()
        if (last in "”’』」》）)]\"'") {
            val inner = t.dropLastWhile { it in "”’』」》）)]\"' " }
            if (inner.isEmpty() || inner.last() !in "。！？!?；;….") return false
        }
        // 排除常见英文缩写（如 Mr. Dr. vs. e.g.）
        if (Regex("\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|vs|etc|e\\.g|i\\.e)\\.$", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return false
        }
        return true
    }

    /**
     * 判断前一段与后一段是否处于**同一个未完句子**中（即使中间被错误插入了换行或空行）：
     * 1. 后一段以英文小写字母开头（如 `wanted to meet` + `me.`，或 `are taught` + `by a non-specialist`）；
     * 2. 前一段以逗号、顿号、冒号、分号、破折号、连字符或开括号结尾（如 `good feedback,` + `I figured...`）；
     * 3. 前一段以英文介词、冠词、连词、助动词等无法收尾的虚词结尾（如 `water pump on` + `Broad Street.`）。
     */
    internal fun isDefinitelyIncomplete(prevText: String, nextText: String): Boolean {
        val p = prevText.trim()
        val n = nextText.trim()
        if (p.isEmpty() || n.isEmpty()) return false

        // 1. 下一段以小写英文字母开头，必定是上一句的延续
        if (n.first() in 'a'..'z') return true

        // 2. 上一段以句中标点（逗号、冒号、连字符、破折号、开括号/引号）结尾
        if (p.last() in ",，、:：;；—–-([（【“‘") return true

        // 3. 上一段以英文虚词（介词、连词、冠词、代词、助动词）结尾，语法上不可能结束句子
        val incompleteTailWords = Regex(
            "\\b(the|a|an|to|of|in|on|at|by|for|with|from|into|onto|upon|about|above|below|under|over|" +
                "between|among|through|during|before|after|since|until|while|where|when|who|whom|whose|" +
                "which|that|this|these|those|my|your|his|her|its|our|their|and|or|but|nor|so|yet|as|if|" +
                "than|because|although|though|unless|whether|is|are|was|were|be|been|being|have|has|had|" +
                "do|does|did|will|would|shall|should|can|could|may|might|must|ought|need|wanted|going|" +
                "trying|able|ready|likely|sure|certain|very|too|quite|rather|almost|enough|even|just|" +
                "only|also|still|already)$",
            RegexOption.IGNORE_CASE
        )
        return incompleteTailWords.containsMatchIn(p)
    }

    /**
     * 判断一段没有句末标点的文本是否是独立标题（如 "How to use physics to make sense of the messiness of life"）：
     * - 长度适中（<= 85 字符）
     * - 不包含逗号、分号，且中间没有已经结束的句子（如 "...know its cause. But he" 不是标题，而是段内折行）
     * - 与下一行不构成未完句延续
     */
    private fun isStandaloneHeading(prevText: String, nextText: String): Boolean {
        val p = prevText.trim()
        if (p.isEmpty() || p.length > 85) return false
        if (isDefinitelyIncomplete(p, nextText)) return false
        if (p.contains(',') || p.contains(';') || p.contains('，') || p.contains('；')) return false
        // 如果中间已经有句号+新句子开头（例如 "When cholera... didn't know its cause. But he"），说明是正文行而非标题
        if (Regex("[.!?。！？]\\s+[A-Z\\u4E00-\\u9FFF]").containsMatchIn(p)) return false
        return true
    }

    private fun isCjkChar(c: Char): Boolean =
        (c in '\u4E00'..'\u9FFF') || (c in '\u3000'..'\u303F') || (c in '\uFF00'..'\uFFEF')

    /**
     * 清理 PDFBox 坐标估算产生的多余空格：
     * - 多个连续水平空白压缩为单个空格
     * - 标点符号前多余空格（如 "word , " -> "word, "）
     * - 单/双引号与括号内侧的多余空格（如 "‘ science ’" -> "‘science’", "( word )" -> "(word)"）
     * - 中文字符之间莫名被插入的空格（如 "中 文 测 试" -> "中文测试"）
     */
    internal fun cleanPdfSpacing(text: String): String {
        var s = text.replace(Regex("[ \\t]+"), " ")
        // 标点前多余空格（如 "word , " -> "word, "）
        s = s.replace(Regex("\\s+([,.:;?!，。：；？！、）】”\\]])"), "$1")
        // 开括号/开引号后多余空格（如 "( word" -> "(word", "‘ science" -> "‘science"）
        s = s.replace(Regex("([（【“‘(\\[])\\s+"), "$1")
        // 闭括号/闭引号前多余空格（如 "word )" -> "word)", "science ’" -> "science’"）
        s = s.replace(Regex("\\s+([)）】”’\\]])"), "$1")
        // 英文撇号缩略形式如果前后被误插空格（如 "didn ’ t" -> "didn’t"）
        s = s.replace(Regex("([a-zA-Z])\\s+([’'])\\s*([a-zA-Z])"), "$1$2$3")
        // 汉字间的孤立空格消除
        s = s.replace(Regex("([\\u4E00-\\u9FFF])\\s+([\\u4E00-\\u9FFF])"), "$1$2")
        s = s.replace(Regex("([\\u4E00-\\u9FFF])\\s+([\\u4E00-\\u9FFF])"), "$1$2")
        return s.trim()
    }
}

fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
