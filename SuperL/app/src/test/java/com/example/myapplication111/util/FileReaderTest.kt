package com.example.myapplication111.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 PDF 文本格式化与清洗逻辑：
 * 1. 消除 PDFBox 常见空白瑕疵（标点前空格、单引号/括号内壁空格、汉字间孤立空格）；
 * 2. 修复排版连字符断词（区分真正的连字符复合词如 14- to / non-specialist 与断词连字符 infor-mation）；
 * 3. 即便输入文本在句中被错误插入了空行（如 "wanted to meet\n\nme."），也能根据语法特征自动愈合为连续自然段；
 * 4. 严谨保留真正的自然段落空行（\n\n）与独立标题。
 */
class FileReaderTest {

    @Test
    fun `Day01真实案例：句中即使被误插空行也能自动愈合为自然段落`() {
        val brokenInput = """How to use physics to make sense of the
messiness of life

But then I got a phone call, telling me
that one of the senior American
partners wanted to meet

me. That was unusual; but then again,
I’d been working so hard and getting
such good feedback,

I figured it couldn’t be anything too
bad.

And more than half of GCSE physics
classes in England (taken by 14- to
16-year-olds) are taught

by a non-specialist, with a shortage of
some 3,500 physics teachers nationally."""

        val expected = """How to use physics to make sense of the messiness of life

But then I got a phone call, telling me that one of the senior American partners wanted to meet me. That was unusual; but then again, I’d been working so hard and getting such good feedback, I figured it couldn’t be anything too bad.

And more than half of GCSE physics classes in England (taken by 14- to 16-year-olds) are taught by a non-specialist, with a shortage of some 3,500 physics teachers nationally."""

        val actual = FileReader.formatExtractedPdfText(brokenInput)
        assertEquals(expected, actual)
        assertFalse("meet 与 me 之间绝不能有换行", actual!!.contains("meet\n"))
    }

    @Test
    fun `PDF中跨行英文折行平滑合并为一个自然段`() {
        val raw = """The manager said that they would
want to meet
me tomorrow afternoon.

This is the second paragraph. It spans
across multiple lines."""

        val expected = """The manager said that they would want to meet me tomorrow afternoon.

This is the second paragraph. It spans across multiple lines."""

        assertEquals(expected, FileReader.formatExtractedPdfText(raw))
    }

    @Test
    fun `PDF连字符跨行单词自动拼接且保留合法复合词连字符`() {
        val raw = """This system provides infor-
mation and is taught by a non-
specialist."""

        val expected = "This system provides information and is taught by a non-specialist."

        assertEquals(expected, FileReader.formatExtractedPdfText(raw))
    }

    @Test
    fun `消除标点前空格与单双引号括号内多余空白`() {
        val raw = "Hello , world ! Being on the ‘ science ’ track or ( with parens ) . And [ brackets ] ."
        val expected = "Hello, world! Being on the ‘science’ track or (with parens). And [brackets]."

        assertEquals(expected, FileReader.formatExtractedPdfText(raw))
    }

    @Test
    fun `消除汉字之间孤立插入的空格`() {
        val raw = "这 是 一 段 测 试 文 本 ， 中 间 不 应 该 有 空 格 。"
        val expected = "这是一段测试文本，中间不应该有空格。"

        assertEquals(expected, FileReader.formatExtractedPdfText(raw))
    }

    @Test
    fun `保留编号列表与项目符号各自成行`() {
        val raw = """Introduction:
1. First step of the process
2. Second step
- Additional note"""

        val expected = """Introduction:

1. First step of the process

2. Second step

- Additional note"""

        assertEquals(expected, FileReader.formatExtractedPdfText(raw))
    }

    @Test
    fun `空输入安全处理`() {
        assertEquals("", FileReader.formatExtractedPdfText(""))
        assertEquals("", FileReader.formatExtractedPdfText("   \n\n  "))
        assertEquals(null, FileReader.formatExtractedPdfText(null))
    }
}
