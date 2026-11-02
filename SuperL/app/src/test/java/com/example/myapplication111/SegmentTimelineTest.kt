package com.example.myapplication111

import com.example.myapplication111.tts.LyricLine
import com.example.myapplication111.tts.SegmentTiming
import com.example.myapplication111.tts.SegmentTimeline
import com.example.myapplication111.tts.TimingSentence
import com.example.myapplication111.tts.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单元测试：断句与行时间计算都是不碰 Android API 的纯逻辑。
 *
 * 逐句高光和拖动进度条全建立在这两个函数之上：断句错了正文会切碎/丢字，
 * 时间算错了高光会指错句子 —— 所以值得单独锁住行为。
 */
class SegmentTimelineTest {

    // ------------------------------------------------------------------
    // 断句
    // ------------------------------------------------------------------

    private fun assertSentences(text: String, expected: List<String>) {
        assertEquals(expected, SegmentTimeline.splitSentences(text))
    }

    /** 忽略空白后，断句前后内容必须一致——不能吞字、不能重复。 */
    private fun assertContentPreserved(text: String) {
        assertEquals(
            text.filterNot { it.isWhitespace() },
            SegmentTimeline.splitSentences(text).joinToString("").filterNot { it.isWhitespace() }
        )
    }

    @Test
    fun `中文按句末标点断句且标点留在行尾`() {
        assertSentences(
            "第一句话。第二句话！第三句话？",
            listOf("第一句话。", "第二句话！", "第三句话？")
        )
    }

    @Test
    fun `英文句点后跟空白才断句`() {
        assertSentences(
            "Hello world. How are you? I am fine!",
            listOf("Hello world.", "How are you?", "I am fine!")
        )
    }

    @Test
    fun `小数点和缩写不会被切断`() {
        // 3.14 后面不是空白，不断
        assertSentences("圆周率是3.14没错", listOf("圆周率是3.14没错"))
        assertContentPreserved("value is 3.14159 and more")
    }

    @Test
    fun `换行是断句点`() {
        assertSentences(
            "第一段没有标点\n第二段也没有标点",
            listOf("第一段没有标点", "第二段也没有标点")
        )
    }

    @Test
    fun `省略号与连续标点按一个断点处理`() {
        assertSentences("你在说什么……我不知道？！", listOf("你在说什么……", "我不知道？！"))
    }

    @Test
    fun `句末引号归入当前句而不落到下一行开头`() {
        assertSentences(
            "他说：\u201c走吧。\u201d然后离开了。",
            listOf("他说：\u201c走吧。\u201d", "然后离开了。")
        )
    }

    @Test
    fun `分号也断句`() {
        assertSentences("动作要快；姿势要帅。", listOf("动作要快；", "姿势要帅。"))
    }

    @Test
    fun `空白与空行不会产出空句`() {
        assertSentences("  第一句。  \n\n   第二句。  ", listOf("第一句。", "第二句。"))
        assertTrue(SegmentTimeline.splitSentences("   \n  \n ").isEmpty())
    }

    @Test
    fun `断句不丢内容`() {
        listOf(
            "这是一段很长的中文文本。里面有各种标点！还有英文 words. 和数字 3.14。",
            "No punctuation at the end\nsecond line.",
            "mixed。\n\nEnglish! 中文？e.g. style",
            "x"
        ).forEach { assertContentPreserved(it) }
    }

    // ------------------------------------------------------------------
    // 行时间：段边界精确，段内按权重分摊
    // ------------------------------------------------------------------

    @Test
    fun `单句段的时间就是段的精确时间`() {
        val lines = SegmentTimeline.buildLyricLines(
            listOf(SegmentTiming("一句话。", 1000, 5000))
        )
        assertEquals(listOf(LyricLine("一句话。", 1000, 6000)), lines)
    }

    @Test
    fun `多句段内时间单调递增且覆盖整段`() {
        val segment = SegmentTiming("第一句比较长一些。短句。最后一句也不短！", 10_000, 9_000)
        val lines = SegmentTimeline.buildLyricLines(listOf(segment))

        assertEquals(3, lines.size)
        assertEquals(10_000L, lines.first().startMs)
        assertEquals(19_000L, lines.last().endMs)
        // 行区间连续且单调：每行的起点等于上一行的终点
        for (i in 1 until lines.size) {
            assertEquals(lines[i - 1].endMs, lines[i].startMs)
            assertTrue(lines[i].endMs > lines[i].startMs)
        }
    }

    @Test
    fun `段边界是精确锚点`() {
        val lines = SegmentTimeline.buildLyricLines(
            listOf(
                SegmentTiming("第一段。第二段。", 0, 10_000),
                SegmentTiming("第二段开头。完。", 10_000, 5_000)
            )
        )
        // 第二段第一句的起点必须精确等于第一段终点，不因段内估算漂移
        assertEquals(10_000L, lines[2].startMs)
        assertEquals(15_000L, lines.last().endMs)
    }

    @Test
    fun `零时长段只断句不给时间`() {
        val lines = SegmentTimeline.buildLyricLines(
            listOf(SegmentTiming("甲。乙。", 5_000, 0))
        )
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startMs == 5_000L && it.endMs == 5_000L })
    }

    @Test
    fun `估算行覆盖全部时长`() {
        val text = "第一句。第二句说得更长一些所以分到更多时间。第三句！"
        val lines = SegmentTimeline.buildEstimatedLines(text, 60_000)

        assertTrue(lines.size >= 3)
        assertEquals(0L, lines.first().startMs)
        assertEquals(60_000L, lines.last().endMs)
        for (i in 1 until lines.size) {
            assertEquals(lines[i - 1].endMs, lines[i].startMs)
        }
        // 更长的句子应分到更大的区间
        val longest = lines.maxBy { it.endMs - it.startMs }
        assertTrue(longest.text.contains("更长"))
    }

    @Test
    fun `总时长未知时估算行起止都是零`() {
        val lines = SegmentTimeline.buildEstimatedLines("甲。乙。", 0)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startMs == 0L && it.endMs == 0L })
    }

    // ------------------------------------------------------------------
    // 当前行定位
    // ------------------------------------------------------------------

    @Test
    fun `当前行按起始时间定位`() {
        val lines = listOf(
            LyricLine("一", 0, 1000),
            LyricLine("二", 1000, 3000),
            LyricLine("三", 3000, 5000)
        )
        assertEquals(0, SegmentTimeline.currentLineIndex(lines, 0))
        assertEquals(0, SegmentTimeline.currentLineIndex(lines, 999))
        assertEquals(1, SegmentTimeline.currentLineIndex(lines, 1000))
        assertEquals(2, SegmentTimeline.currentLineIndex(lines, 4500))
        // 播放位置略超最后一行的起点（取整误差）也落在最后一行
        assertEquals(2, SegmentTimeline.currentLineIndex(lines, 10_000))
    }

    @Test
    fun `空列表与起始前位置返回 -1`() {
        assertEquals(-1, SegmentTimeline.currentLineIndex(emptyList(), 0))
        assertEquals(
            -1,
            SegmentTimeline.currentLineIndex(listOf(LyricLine("一", 1000, 2000)), 500)
        )
    }

    // ------------------------------------------------------------------
    // 词级时间戳 → 精确句级时间
    // ------------------------------------------------------------------

    @Test
    fun `词级时间戳映射到句子给出精确起点`() {
        val words = listOf(
            WordTiming(0, 800, "今天天气"),
            WordTiming(800, 400, "很好"),
            WordTiming(1600, 500, "我们"),
            WordTiming(2100, 900, "去公园吧")
        )
        val sentences = SegmentTimeline.sentencesFromWords(
            "今天天气很好。我们去公园吧！", words
        )

        assertEquals(2, sentences.size)
        assertEquals("今天天气很好。", sentences[0].text)
        assertEquals("我们去公园吧！", sentences[1].text)
        // 每句起点 = 该句第一个词的 offset；终点 = 下一句起点（最后一句到词的结束）
        assertEquals(0L, sentences[0].startMs)
        assertEquals(1600L, sentences[0].endMs)
        assertEquals(1600L, sentences[1].startMs)
        assertEquals(3000L, sentences[1].endMs)
    }

    @Test
    fun `英文词跨空白映射`() {
        val words = listOf(
            WordTiming(0, 500, "Hello"),
            WordTiming(500, 700, "world"),
            WordTiming(1500, 300, "How"),
            WordTiming(1800, 200, "are"),
            WordTiming(2000, 400, "you")
        )
        val sentences = SegmentTimeline.sentencesFromWords(
            "Hello world. How are you?", words
        )

        assertEquals(2, sentences.size)
        assertEquals(0L, sentences[0].startMs)
        assertEquals(1500L, sentences[1].startMs)
        assertEquals(2400L, sentences[1].endMs)
    }

    @Test
    fun `词文本与原文完全对不上时返回空列表退回估算`() {
        val words = listOf(WordTiming(0, 500, "完全不同的词"))
        assertTrue(
            SegmentTimeline.sentencesFromWords("今天天气很好。", words).isEmpty()
        )
    }

    @Test
    fun `个别词对不上时跳过该词不推翻整体`() {
        val words = listOf(
            WordTiming(0, 800, "今天天气"),
            WordTiming(800, 400, "数字12345"),   // 原文没有，跳过
            WordTiming(1600, 900, "很好")
        )
        val sentences = SegmentTimeline.sentencesFromWords("今天天气很好。", words)
        assertEquals(1, sentences.size)
        assertEquals(0L, sentences[0].startMs)
    }

    // ------------------------------------------------------------------
    // 精确句级时间进入歌词行
    // ------------------------------------------------------------------

    @Test
    fun `有精确句时间时歌词行用精确值并换算成整篇绝对时间`() {
        val segment = SegmentTiming(
            text = "甲。乙。",
            startMs = 10_000, durationMs = 5_000,
            sentences = listOf(
                TimingSentence("甲。", 0, 1_000),
                TimingSentence("乙。", 1_000, 2_000)
            )
        )
        val lines = SegmentTimeline.buildLyricLines(listOf(segment))
        assertEquals(
            listOf(
                LyricLine("甲。", 10_000, 11_000),
                LyricLine("乙。", 11_000, 12_000)
            ),
            lines
        )
    }

    @Test
    fun `精确句时间超出段时长会被钳制在段区间内`() {
        val segment = SegmentTiming(
            text = "甲。乙。",
            startMs = 0, durationMs = 2_000,
            sentences = listOf(
                TimingSentence("甲。", 0, 1_000),
                TimingSentence("乙。", 1_500, 99_999)
            )
        )
        val lines = SegmentTimeline.buildLyricLines(listOf(segment))
        assertEquals(2_000L, lines[1].endMs)
    }

    @Test
    fun `精确句时间不自洽时退回按字数估算`() {
        val segment = SegmentTiming(
            text = "第一句长一些。短。还是长一点！",
            startMs = 0, durationMs = 9_000,
            sentences = listOf(
                TimingSentence("第一句长一些。", 3_000, 4_000),  // 起点倒序 → 弃用
                TimingSentence("短。", 0, 1_000)
            )
        )
        val lines = SegmentTimeline.buildLyricLines(listOf(segment))
        assertEquals(3, lines.size)
        assertEquals(0L, lines.first().startMs)
        assertEquals(9_000L, lines.last().endMs)
        for (i in 1 until lines.size) {
            assertEquals(lines[i - 1].endMs, lines[i].startMs)
        }
    }

    @Test
    fun `估算时标点停顿折进权重`() {
        // 两句字数相同，一句全是逗号停顿、一句没有标点：有停顿的应分到更长时间
        val lines = SegmentTimeline.buildEstimatedLines(
            "一二三四，五六七八，九十二三。丙丁戊己庚辛壬。",
            20_000
        )
        assertEquals(2, lines.size)
        assertTrue(lines[0].endMs - lines[0].startMs > lines[1].endMs - lines[1].startMs)
    }
}
