package com.example.myapplication111.tts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一段合成音频（对应一次 TTS 请求的文本）在整篇音频里的时间区间。
 *
 * @param text 这一段的合成输入文本（用于把段再切成句子做逐句展示）
 * @param startMs 在整篇音频里的起点
 * @param durationMs 时长
 * @param sentences 段内逐句的**精确**时间（相对段起点，毫秒）。只在服务商返回
 *   词级时间戳时才有（如 Edge TTS 的 WordBoundary）；为空表示这段没有精确
 *   数据，调用方退回按字数权重估算。
 */
data class SegmentTiming(
    val text: String,
    val startMs: Long,
    val durationMs: Long,
    val sentences: List<TimingSentence> = emptyList()
)

/** 段内一句的精确时间区间（相对所在段的起点）。 */
data class TimingSentence(val text: String, val startMs: Long, val endMs: Long)

/** 歌词式展示的一行：一句文本 + 它在整篇音频里的时间区间。 */
data class LyricLine(val text: String, val startMs: Long, val endMs: Long)

/**
 * 分段时间轴：整篇音频是"逐段合成再拼接"出来的，段的边界在合并时是精确已知的
 * （每段的时长可测）。把 [SegmentTiming] 列表以 JSON sidecar 的形式和整篇音频
 * 存在一起，播放页就能根据当前播放位置反查"正在读哪一句"并高亮。
 *
 * 句级时间分两档：
 * - 服务商返回了词级时间戳（Edge TTS 的 WordBoundary）时，由 [sentencesFromWords]
 *   得到段内每句的**精确**时间，存在 [SegmentTiming.sentences] 里；
 * - 没有（其他服务商、或启用前的老缓存）时，段内各句按字数权重估算 ——
 *   TTS 语速整体均匀，段边界又是硬锚点，误差不会跨段累积。
 */
object SegmentTimeline {

    // ------------------------------------------------------------------
    // JSON 落盘（与整篇音频文件放在一起的 sidecar）
    // ------------------------------------------------------------------

    fun write(target: File, segments: List<SegmentTiming>): Boolean = try {
        target.parentFile?.mkdirs()
        target.writeText(toJson(segments), Charsets.UTF_8)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    fun read(source: File): List<SegmentTiming>? = try {
        if (source.exists() && source.length() > 0) fromJson(source.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    private fun toJson(segments: List<SegmentTiming>): String {
        val arr = JSONArray()
        segments.forEach { timing ->
            val obj = JSONObject()
                .put("t", timing.text)
                .put("s", timing.startMs)
                .put("d", timing.durationMs)
            if (timing.sentences.isNotEmpty()) {
                val lines = JSONArray()
                timing.sentences.forEach { sentence ->
                    lines.put(
                        JSONObject()
                            .put("t", sentence.text)
                            .put("s", sentence.startMs)
                            .put("e", sentence.endMs)
                    )
                }
                obj.put("ly", lines)
            }
            arr.put(obj)
        }
        return JSONObject().put("v", 2).put("segments", arr).toString()
    }

    private fun fromJson(raw: String): List<SegmentTiming>? {
        return try {
            val arr = JSONObject(raw).optJSONArray("segments") ?: return null
            val list = ArrayList<SegmentTiming>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val text = obj.optString("t")
                val start = obj.optLong("s")
                val duration = obj.optLong("d")
                // 字段残缺的 sidecar 不可信：高光会整体错位，宁可不用
                if (text.isBlank() || duration <= 0) return null
                // "ly"（v2 起才有）坏了只丢精确句子、退回该段估算，不影响整份 sidecar
                val sentences = parseSentenceLines(obj.optJSONArray("ly"))
                list.add(SegmentTiming(text, start, duration, sentences))
            }
            if (list.isEmpty()) null else list
        } catch (e: Exception) {
            null
        }
    }

    /** sidecar 里段内精确句子的 "ly" 数组；缺失或残缺返回空列表。 */
    private fun parseSentenceLines(arr: JSONArray?): List<TimingSentence> {
        if (arr == null) return emptyList()
        val out = ArrayList<TimingSentence>(arr.length())
        try {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val text = obj.optString("t")
                val start = obj.optLong("s")
                val end = obj.optLong("e")
                if (text.isBlank() || start < 0 || end < start) return emptyList()
                out.add(TimingSentence(text, start, end))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    // ------------------------------------------------------------------
    // 断句（歌词式逐句展示）
    // ------------------------------------------------------------------

    /** 句末标点：出现即断句（连续出现按一个断点处理）。 */
    private const val BREAK_CHARS = "。！？；…"
    /** 断句后紧跟的收尾符号：归入当前句，避免下一行以引号/括号开头。 */
    private const val CLOSERS = "”’』」》）】>\"'"

    /**
     * 把文本切成句子（保留句末标点）。规则：
     * - 中文句末标点（。！？；…）必断；
     * - 英文 .!? 只有后面跟空白或到结尾才断（避免切碎小数点 3.14 这类数字）；
     * - 换行视为断点（段落边界）。
     */
    fun splitSentences(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            buffer.append(c)
            i++

            val isBreak = when {
                c == '\n' -> true
                BREAK_CHARS.indexOf(c) >= 0 -> true
                (c == '.' || c == '!' || c == '?') && (
                    i == normalized.length ||
                        normalized[i] == ' ' ||
                        normalized[i] == '\n' ||
                        normalized[i] == '\t'
                    ) -> true
                else -> false
            }
            if (!isBreak) continue

            // 断点后紧跟的空白、连续句末标点（？！、……）与收尾引号/括号一并
            // 消费进当前句：句子不带首尾空白，也不会拆出"！"、"……"这样的孤立符号行
            while (i < normalized.length) {
                val n = normalized[i]
                if (n == ' ' || n == '\t' || n == '\n') {
                    i++
                    continue
                }
                if (CLOSERS.indexOf(n) >= 0 || BREAK_CHARS.indexOf(n) >= 0) {
                    buffer.append(n)
                    i++
                    continue
                }
                break
            }
            if (buffer.isNotBlank()) sentences.add(buffer.toString().trim())
            buffer.clear()
        }
        if (buffer.isNotBlank()) sentences.add(buffer.toString().trim())
        return sentences
    }

    // ------------------------------------------------------------------
    // 行时间计算
    // ------------------------------------------------------------------

    /**
     * 由分段时间轴构造歌词行。段内每句优先用 [SegmentTiming.sentences] 的精确
     * 时间（相对段起点换算成整篇绝对时间）；没有精确数据时按字数权重分摊。
     */
    fun buildLyricLines(segments: List<SegmentTiming>): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (segment in segments) {
            exactLinesFor(segment)?.let {
                lines.addAll(it)
                continue
            }
            val sentences = splitSentences(segment.text)
            if (sentences.isEmpty()) continue
            if (sentences.size == 1) {
                lines.add(LyricLine(sentences[0], segment.startMs, segment.startMs + segment.durationMs))
                continue
            }
            distribute(sentences, segment.startMs, segment.durationMs, lines)
        }
        return lines
    }

    /**
     * 段内精确句子时间 → 整篇绝对时间的歌词行，全部钳制在段区间内。
     * 数据缺失、不自洽（起点倒序）或段时长未知时返回 null，调用方退回估算。
     */
    private fun exactLinesFor(segment: SegmentTiming): List<LyricLine>? {
        if (segment.sentences.isEmpty() || segment.durationMs <= 0) return null
        val segmentStart = segment.startMs
        val segmentEnd = segment.startMs + segment.durationMs
        val out = ArrayList<LyricLine>(segment.sentences.size)
        for (sentence in segment.sentences) {
            if (sentence.text.isBlank()) return null
            val start = (segmentStart + sentence.startMs).coerceIn(segmentStart, segmentEnd)
            val end = (segmentStart + sentence.endMs).coerceIn(start, segmentEnd)
            if (out.isNotEmpty() && start < out.last().startMs) return null
            out.add(LyricLine(sentence.text, start, end))
        }
        return out
    }

    /**
     * 把服务商的词级时间戳映射到断句结果，得到每句的精确时间（相对段音频起点）。
     *
     * 做法：先按"忽略空白"把每个句子和每个词定位到原文的字符区间（词文本不含
     * 标点、断句会吞断点后的空白，所以不能直接 indexOf），词落在哪个句子的区间
     * 就属于哪句；每句的起点 = 它第一个词的 offset，终点 = 下一句的起点。
     * 定位整体对不上（服务商返回的词和原文对不上号）时返回空列表，宁可用估算。
     */
    fun sentencesFromWords(segmentText: String, words: List<WordTiming>): List<TimingSentence> {
        val sentences = splitSentences(segmentText)
        if (sentences.isEmpty() || words.isEmpty()) return emptyList()

        // 1) 各句在原文中的字符区间 [start, end)
        val starts = IntArray(sentences.size)
        val ends = IntArray(sentences.size)
        var cursor = 0
        for (i in sentences.indices) {
            val start = indexOfIgnoringWhitespace(segmentText, sentences[i], cursor)
            val end = if (start >= 0) matchEndIgnoringWhitespace(segmentText, start, sentences[i]) else -1
            if (start < 0 || end < 0) return emptyList()
            starts[i] = start
            ends[i] = end
            cursor = end
        }

        // 2) 词 → 句归属，记下每句第一个词的时间
        val firstWordMs = LongArray(sentences.size) { -1L }
        var wordCursor = 0
        var matched = 0
        var lastWordEndMs = 0L
        for (word in words) {
            val pos = indexOfIgnoringWhitespace(segmentText, word.text, wordCursor)
            if (pos < 0) continue // 个别词对不上（如数字被服务商改写）：跳过，不推翻整体
            wordCursor = pos + word.text.length
            val index = sentenceIndexFor(starts, ends, pos)
            if (index >= 0 && firstWordMs[index] < 0) firstWordMs[index] = word.offsetMs
            matched++
            lastWordEndMs = maxOf(lastWordEndMs, word.offsetMs + word.durationMs)
        }
        if (matched == 0) return emptyList()

        // 3) 没有词落进去的句子（整句只有标点这类极端情况）用下一句的起点回填，
        //    再做一遍单调化，保证起点序列不回退
        for (i in sentences.indices.reversed()) {
            if (firstWordMs[i] < 0L) {
                firstWordMs[i] = if (i + 1 < sentences.size) firstWordMs[i + 1] else lastWordEndMs
            }
        }
        for (i in 1 until sentences.size) {
            if (firstWordMs[i] < firstWordMs[i - 1]) firstWordMs[i] = firstWordMs[i - 1]
        }

        val out = ArrayList<TimingSentence>(sentences.size)
        for (i in sentences.indices) {
            val end = if (i + 1 < sentences.size) firstWordMs[i + 1] else lastWordEndMs
            out.add(TimingSentence(sentences[i], firstWordMs[i], maxOf(end, firstWordMs[i])))
        }
        return out
    }

    /** 词的原文位置落在哪个句子区间；越界（尾部残词）归入最后一句。 */
    private fun sentenceIndexFor(starts: IntArray, ends: IntArray, pos: Int): Int {
        for (i in starts.indices) {
            if (pos >= starts[i] && pos < ends[i]) return i
        }
        return if (pos >= ends.last()) starts.size - 1 else -1
    }

    /**
     * 从 [from] 起，按"忽略空白"的方式在 [text] 里找 [target] 的出现位置。
     * 词文本不带标点、句子会被断句逻辑吞掉首尾空白，因此都不能用严格 indexOf。
     * 找不到返回 -1。
     */
    private fun indexOfIgnoringWhitespace(text: String, target: String, from: Int): Int {
        if (target.isEmpty()) return -1
        var start = from.coerceAtLeast(0)
        while (start < text.length) {
            var i = start
            var j = 0
            while (j < target.length) {
                while (i < text.length && text[i].isWhitespace()) i++
                while (j < target.length && target[j].isWhitespace()) j++
                if (j >= target.length) break
                if (i >= text.length || text[i] != target[j]) break
                i++
                j++
            }
            var jj = j
            while (jj < target.length && target[jj].isWhitespace()) jj++
            if (jj >= target.length) return start
            start++
        }
        return -1
    }

    /** 从 [start] 起（忽略空白地）匹配完 [target] 后停在 [text] 里的下标；对不上返回 -1。 */
    private fun matchEndIgnoringWhitespace(text: String, start: Int, target: String): Int {
        var i = start
        var j = 0
        while (j < target.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            while (j < target.length && target[j].isWhitespace()) j++
            if (j >= target.length) break
            if (i >= text.length || text[i] != target[j]) return -1
            i++
            j++
        }
        return i
    }

    /**
     * 没有精确时间轴时的兜底：把整篇时长按字数权重摊到每个句子。
     * [totalDurationMs] <= 0 时只给断句、不给时间（各行起止都是 0）。
     */
    fun buildEstimatedLines(text: String, totalDurationMs: Long): List<LyricLine> =
        buildLyricLines(listOf(SegmentTiming(text, 0L, totalDurationMs.coerceAtLeast(0L))))

    /**
     * 当前播放位置落在第几行。要求 [lines] 按 startMs 非降序且各行时长为正
     * （[buildLyricLines] 的输出满足）；找不到返回 -1。
     */
    fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var index = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= positionMs) index = i else break
        }
        return index
    }

    /** 把 [durationMs] 按句子字数权重切分，产出 [startMs, startMs+durationMs] 内的连续区间。 */
    private fun distribute(
        sentences: List<String>,
        startMs: Long,
        durationMs: Long,
        out: MutableList<LyricLine>
    ) {
        if (durationMs <= 0) {
            sentences.forEach { out.add(LyricLine(it, startMs, startMs)) }
            return
        }
        val weights = sentences.map { weightOf(it) }
        val total = weights.sum()
        var acc = 0.0
        for (j in sentences.indices) {
            val lineStart = startMs + (acc / total * durationMs).toLong()
            acc += weights[j]
            val lineEnd = if (j == sentences.size - 1) {
                startMs + durationMs
            } else {
                startMs + (acc / total * durationMs).toLong()
            }
            out.add(LyricLine(sentences[j], lineStart, lineEnd))
        }
    }

    /**
     * 朗读时长权重：CJK/全角字符每字记 1，半角字符记 0.55（一个英文单词
     * 约两三个音节）。只用于相对摊配，绝对值无所谓。
     *
     * 标点还要额外加上"停顿权重"：TTS 在逗号/句号处会插入可闻停顿
     * （中文语音大约 200ms / 350ms 量级），不把它折进权重，标点多的段落里
     * 越靠后的句子估算起点越提前，高光系统性超前。
     */
    private fun weightOf(text: String): Double {
        var weight = 0.0
        for (c in text) {
            if (c.isWhitespace()) continue
            weight += if (c.code > 0x2E7F) 1.0 else 0.55
            when (c) {
                '，', '、', '；', ',', ';', '：', ':' -> weight += 0.9
                '。', '！', '？', '…', '.', '!', '?' -> weight += 1.6
            }
        }
        return if (weight <= 0.0) 1.0 else weight
    }
}
