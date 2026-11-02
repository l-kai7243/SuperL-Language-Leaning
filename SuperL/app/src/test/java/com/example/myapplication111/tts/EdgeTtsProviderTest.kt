package com.example.myapplication111.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EdgeTtsProvider 纯函数的 JVM 单元测试。
 *
 * Sec-MS-GEC v2 的期望值来自 edge-tts（Python）7.3.1 官方实现的实算结果，
 * 逐位对拍——token 算错一个字节整条链路就是 403，所以值得锁死。
 */
class EdgeTtsProviderTest {

    // ------------------------------------------------------------------
    // Sec-MS-GEC v2：与 edge-tts 7.3.1 实算值对拍
    // ------------------------------------------------------------------

    @Test
    fun `Sec-MS-GEC v2 与 edge-tts 对拍一致`() {
        val cases = mapOf(
            0L to 0L to
                "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            1759500000L to 0L to
                "4661647A1C2B436C-9307EC378550B66D5825574ED5257F0908465A9D80F1A3867FF269D2E5D011D0",
            // +37 秒仍在同一个 5 分钟窗内，token 应当相同
            1759500000L to 37L to
                "4661647A1C2B436C-9307EC378550B66D5825574ED5257F0908465A9D80F1A3867FF269D2E5D011D0",
            1893456000L to -5L to
                "03847CC1227505DB-051302B2329126785094AE11A586D81D8C860D1E86393BF8DAF06ADAA55F6A76",
            1761234567L to 0L to
                "C0FD7D68F11A8CA8-2E7E864BDA8F96B586D6661420FF68D92FA675776F29AE91C3CD4A9102030C74",
            1792000000L to -123L to
                "AB2CB5A47045A3E5-6845D3D723C58B49691D359EC8F0FEC231AC8087A893B2B6DDCBC49C8F0C787D"
        )
        cases.forEach { (params, expected) ->
            val (unix, skew) = params
            assertEquals("unix=$unix skew=$skew", expected, EdgeTtsProvider.secMsGec(unix, skew))
        }
    }

    @Test
    fun `Sec-MS-GEC v2 输出为 16 位-64 位大写十六进制`() {
        val token = EdgeTtsProvider.secMsGec(1759500000L, 0L)
        assertEquals(81, token.length)
        assertEquals('-', token[16])
        val parts = token.split("-")
        assertEquals(16, parts[0].length)
        assertEquals(64, parts[1].length)
        assertTrue(token.all { it in "0123456789ABCDEF-" })
    }

    // ------------------------------------------------------------------
    // 语速 -> SSML prosody rate
    // ------------------------------------------------------------------

    @Test
    fun `语速转 rate 百分比`() {
        assertEquals("+0%", EdgeTtsProvider.toRateParam(1.0f))
        assertEquals("-50%", EdgeTtsProvider.toRateParam(0.5f))
        assertEquals("+100%", EdgeTtsProvider.toRateParam(2.0f))
        assertEquals("+25%", EdgeTtsProvider.toRateParam(1.25f))
        // 越界值被钳制到 [0.5, 2.0]
        assertEquals("-50%", EdgeTtsProvider.toRateParam(0.1f))
        assertEquals("+100%", EdgeTtsProvider.toRateParam(3.0f))
    }

    // ------------------------------------------------------------------
    // 文本清洗与 XML 转义
    // ------------------------------------------------------------------

    @Test
    fun `控制字符被替换为空格`() {
        // 垂直制表符 \u000B 是 OCR 文本常见字符，服务端不支持
        assertEquals("a b c", EdgeTtsProvider.cleanIncompatibleCharacters("a\u000Bb\u000Cc"))
        // \t(9) \n(10) \r(13) 是合法的，保留
        assertEquals("a\tb\nc\rd", EdgeTtsProvider.cleanIncompatibleCharacters("a\tb\nc\rd"))
        assertEquals("正常文本", EdgeTtsProvider.cleanIncompatibleCharacters("正常文本"))
    }

    @Test
    fun `XML 转义只处理 ampersand 与尖括号`() {
        assertEquals(
            "a &amp; b &lt; c &gt; d",
            EdgeTtsProvider.escapeXmlText("a & b < c > d")
        )
        // 引号不转义：文本在元素内容里，且英文撇号极常见，转义会白白挤占字节预算
        assertEquals("don't \"x\"", EdgeTtsProvider.escapeXmlText("don't \"x\""))
    }

    // ------------------------------------------------------------------
    // 按 UTF-8 字节切分（服务端单条 SSML 上限 4096 字节）
    // ------------------------------------------------------------------

    private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    private fun assertSplitsValid(text: String, limit: Int) {
        val chunks = EdgeTtsProvider.splitByEscapedByteLimit(text, limit)
        assertTrue("不应切出空列表", chunks.isNotEmpty())
        chunks.forEach {
            assertTrue(
                "段字节数 ${utf8Size(it)} 超过上限 $limit",
                utf8Size(it) <= limit
            )
        }
        // 拼回去必须和原文一致（可能丢的只有纯空白段），不能吞字、不能重复
        assertEquals(
            "切分前后内容不一致（limit=$limit）",
            text.filterNot { it.isWhitespace() },
            chunks.joinToString("").filterNot { it.isWhitespace() }
        )
    }

    @Test
    fun `英文长文本按空格断开且不超限`() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(300) // ~13.8KB
        assertSplitsValid(text, 4096)
        val chunks = EdgeTtsProvider.splitByEscapedByteLimit(text, 4096)
        assertTrue("应当切出多段", chunks.size > 2)
        // 优先自然断点：绝大多数段应以空格结尾或以空格开头开头处的词边界
        chunks.dropLast(1).forEach { chunk ->
            val tail = chunk.takeLast(1)
            assertTrue("段应在词边界断开: ...${chunk.takeLast(12)}", tail == " " || tail == "\n")
        }
    }

    @Test
    fun `中文长文本三字节字符不超限`() {
        val text = "这是一段用来测试中文切分的文本，混合English words一起出现。".repeat(200)
        assertSplitsValid(text, 4096)
    }

    @Test
    fun `无空格无换行的超长文本也能切开`() {
        val text = "abcdefghij".repeat(1500) // 15KB 无空白
        assertSplitsValid(text, 4096)
    }

    @Test
    fun `转义实体不被切断`() {
        val text = ("word&plus;more".repeat(350)) // &plus; 是测试用的"实体"形态
        val chunks = EdgeTtsProvider.splitByEscapedByteLimit(text, 4096)
        // 每一段里的 & 后面要么到段尾都是实体的一部分，要么段内已有 ;
        chunks.forEach { chunk ->
            val amp = chunk.lastIndexOf('&')
            if (amp >= 0) {
                val semi = chunk.lastIndexOf(';')
                // 段尾遗留未闭合的 & 是允许的边缘情形（实体跨到下一段），不算切断
                if (semi < amp) {
                    val rest = chunk.substring(amp)
                    assertTrue("实体被从中间切断: $rest", rest.length <= "&amp;".length)
                }
            }
        }
        assertSplitsValid(text, 4096)
    }

    @Test
    fun `emoji 等代理对不被切开`() {
        val text = "hi😀there".repeat(1000)
        val chunks = EdgeTtsProvider.splitByEscapedByteLimit(text, 4096)
        chunks.forEach { chunk ->
            assertFalse("高代理项不能在段尾", chunk.isNotEmpty() && chunk.last().isHighSurrogate())
        }
        assertSplitsValid(text, 4096)
    }

    @Test
    fun `短文本不切分`() {
        assertEquals(
            listOf("hello world"),
            EdgeTtsProvider.splitByEscapedByteLimit("hello world", 4096)
        )
    }

    // ------------------------------------------------------------------
    // 音色名归一化
    // ------------------------------------------------------------------

    @Test
    fun `空白音色回落到默认音色`() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (en-US, EmmaMultilingualNeural)",
            EdgeTtsProvider.normalizeVoice("")
        )
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (en-US, EmmaMultilingualNeural)",
            EdgeTtsProvider.normalizeVoice("  ")
        )
    }

    @Test
    fun `简短音色名转成完整形式`() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (en-US, EmmaMultilingualNeural)",
            EdgeTtsProvider.normalizeVoice("en-US-EmmaMultilingualNeural")
        )
        // 带附加地区段（方言）的音色
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            EdgeTtsProvider.normalizeVoice("zh-CN-liaoning-XiaobeiNeural")
        )
    }

    @Test
    fun `完整音色名原样通过`() {
        val full = "Microsoft Server Speech Text to Speech Voice (en-GB, SoniaNeural)"
        assertEquals(full, EdgeTtsProvider.normalizeVoice(full))
    }

    @Test
    fun `非法音色名返回 null`() {
        assertNull(EdgeTtsProvider.normalizeVoice("tongtong"))
        assertNull(EdgeTtsProvider.normalizeVoice("alloy"))
        assertNotNull(EdgeTtsProvider.normalizeVoice("zh-CN-XiaoxiaoNeural"))
    }
}
