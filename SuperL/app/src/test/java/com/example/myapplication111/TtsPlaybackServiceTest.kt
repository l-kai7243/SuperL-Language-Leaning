package com.example.myapplication111

import com.example.myapplication111.tts.TtsPlaybackService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 纯 JVM 单元测试：分段算法与音频合并都是不碰 Android API 的纯逻辑，
 * 所以能直接在本地跑（./gradlew test），不需要模拟器。
 *
 * 这两块是"整篇连续播放"的地基：分段错了会被服务商拒（超字符上限），
 * 合并错了会直接听出杂音或截断，所以值得单独锁住行为。
 */
class TtsPlaybackServiceTest {

    // ------------------------------------------------------------------
    // 分段：任何一段都不能超过上限，且内容不能丢
    // ------------------------------------------------------------------

    private fun assertSegmentsValid(text: String, limit: Int) {
        val segments = TtsPlaybackService.splitTextIntoSegments(text, limit)
        assertTrue("不应该切出空列表", segments.isNotEmpty())
        segments.forEach {
            assertTrue("段长 ${it.length} 超过了上限 $limit", it.length <= limit)
        }
        // 忽略空白后拼接，必须与原文一致——不能吞字、不能重复
        assertEquals(
            "分段前后内容不一致（limit=$limit）",
            text.filterNot { it.isWhitespace() },
            segments.joinToString("").filterNot { it.isWhitespace() }
        )
    }

    @Test
    fun `英文长文本在各种上限下都合法`() {
        val text = ("The quick brown fox jumps over the lazy dog. ".repeat(300)) +
            "\n\n" + ("Hello world! ".repeat(200))
        listOf(2048, 1024, 500, 300, 137, 30, 7, 1).forEach { assertSegmentsValid(text, it) }
    }

    @Test
    fun `中文长文本在各种上限下都合法`() {
        val text = "这是一段很长的中文文本用来测试分段逻辑是否正确。".repeat(300)
        listOf(1024, 500, 300, 137, 30, 7, 1).forEach { assertSegmentsValid(text, it) }
    }

    @Test
    fun `没有任何分隔符的文本会被硬切且不超限`() {
        val text = "x".repeat(5000)
        listOf(2048, 1024, 500, 137, 30, 7, 1).forEach { assertSegmentsValid(text, it) }
    }

    @Test
    fun `短句连排时不会超出上限`() {
        // ". " 结尾的位置会让朴素实现多出一两个字符，这里专门锁住这个边界
        val text = "Hi. ".repeat(1000)
        listOf(2048, 1024, 500, 300, 137, 30, 7, 1).forEach { assertSegmentsValid(text, it) }
    }

    @Test
    fun `合成归一化平滑处理折行并保留段落空行`() {
        val input = """want to meet
me .want to meet在一行，
me到了下一行。

这是第二段。"""
        val normalized = TtsPlaybackService.normalizeForSynthesis(input)
        assertTrue(normalized.contains("want to meet me .want to meet在一行，me到了下一行。"))
        assertTrue(normalized.contains("\n\n这是第二段。"))
    }

    @Test
    fun `分段切分时优先按句末标点断开而非生硬换行`() {
        val sentence1 = "This is the first sentence that is complete. "
        val sentence2 = "This is the second\nsentence with a line wrap."
        val full = sentence1 + sentence2
        val segments = TtsPlaybackService.splitTextIntoSegments(full, 50)
        assertEquals(2, segments.size)
        assertEquals(sentence1.trim(), segments[0])
    }

    @Test
    fun `短文本不会被切分`() {
        val text = "Hello world."
        assertEquals(listOf(text), TtsPlaybackService.splitTextIntoSegments(text, 1024))
    }

    @Test
    fun `空白文本返回空列表`() {
        assertTrue(TtsPlaybackService.splitTextIntoSegments("   \n  ", 1024).isEmpty())
        assertTrue(TtsPlaybackService.splitTextIntoSegments("", 1024).isEmpty())
    }

    // ------------------------------------------------------------------
    // 合并：WAV 必须逐字节无损，MP3 的 ID3 标签必须被剥掉
    // ------------------------------------------------------------------

    private fun tempFile(name: String) = File.createTempFile(name, null).apply { deleteOnExit() }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun chunk(id: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.US_ASCII))
            write(le32(payload.size))
            write(payload)
            if (payload.size % 2 == 1) write(0)
        }.toByteArray()

    /** 造一个 WAV；[withList] 为 true 时在 data 之前插一个 LIST 块。 */
    private fun wavBytes(samples: Int, withList: Boolean, value: Short = 1000): ByteArray {
        val frames = ByteArrayOutputStream().apply { repeat(samples) { write(le16(value.toInt())) } }
        val fmt = ByteArrayOutputStream().apply {
            write(le16(1)); write(le16(1)); write(le32(24000)); write(le32(48000))
            write(le16(2)); write(le16(16))
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(chunk("fmt ", fmt))
            if (withList) {
                write(
                    chunk(
                        "LIST",
                        "INFOIART".toByteArray() + "test".toByteArray() + byteArrayOf(0)
                    )
                )
            }
            write(chunk("data", frames.toByteArray()))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(le32(body.size + 4))
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write(body)
        }.toByteArray()
    }

    private fun readInt32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** 按 RIFF 块结构找到 data 块内容——和播放器解码时的做法一致。 */
    private fun wavData(file: File): ByteArray {
        val b = file.readBytes()
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = readInt32(b, pos + 4)
            if (id == "data") return b.copyOfRange(pos + 8, pos + 8 + size)
            pos += 8 + size + (size and 1)
        }
        error("没有找到 data 块")
    }

    @Test
    fun `带额外 LIST 块的 WAV 合并后逐字节无损`() {
        // 旧实现硬编码跳过 44 字节头；有 LIST 块时 data 起点更靠后，
        // 会把每段开头的音频数据当成头丢掉——这里锁住不许再犯。
        val parts = listOf(
            tempFile("p1.wav").apply { writeBytes(wavBytes(1000, withList = true, value = 100)) },
            tempFile("p2.wav").apply { writeBytes(wavBytes(2000, withList = true, value = 200)) },
            tempFile("p3.wav").apply { writeBytes(wavBytes(500, withList = true, value = 300)) },
        )
        val dest = tempFile("merged.wav")

        TtsPlaybackService.mergeAudioFiles(parts, dest)

        val expected = parts.flatMap { wavData(it).toList() }.toByteArray()
        assertArrayEquals("合并后的音频数据必须与各段顺序拼接完全一致", expected, wavData(dest))
        assertEquals("总采样数应为 3500（7000 字节）", 7000, wavData(dest).size)

        val header = dest.readBytes()
        assertEquals("RIFF 总长度字段应被重写", header.size - 8, readInt32(header, 4))
    }

    @Test
    fun `普通 44 字节头的 WAV 也能正确合并`() {
        val parts = listOf(
            tempFile("q1.wav").apply { writeBytes(wavBytes(1000, withList = false)) },
            tempFile("q2.wav").apply { writeBytes(wavBytes(500, withList = false)) },
        )
        val dest = tempFile("merged2.wav")

        TtsPlaybackService.mergeAudioFiles(parts, dest)

        assertEquals(3000, wavData(dest).size)
    }

    @Test
    fun `合并后后续 MP3 分段的 ID3v2 标签会被剥掉`() {
        // ID3 标签是二进制元数据，不剥掉会作为一段噪声混进音频里
        fun id3(payload: Int) = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((payload shr 21) and 0x7F).toByte(), ((payload shr 14) and 0x7F).toByte(),
            ((payload shr 7) and 0x7F).toByte(), (payload and 0x7F).toByte()
        ) + ByteArray(payload)

        val partA = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) +
            ByteArray(400) { 'A'.code.toByte() }
        val partB = id3(50) + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) +
            ByteArray(400) { 'B'.code.toByte() }

        val dest = tempFile("merged3.mp3")
        TtsPlaybackService.mergeAudioFiles(
            listOf(
                tempFile("a.mp3").apply { writeBytes(partA) },
                tempFile("b.mp3").apply { writeBytes(partB) },
            ),
            dest
        )

        val merged = dest.readBytes()
        assertFalse("ID3 标签不应该出现在合并结果里", String(merged, Charsets.ISO_8859_1).contains("ID3"))
        assertEquals(partA.size + partB.size - 60, merged.size)
    }

    @Test
    fun `后续分段没有 ID3 标签时不会误删数据`() {
        val partA = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) +
            ByteArray(400) { 'A'.code.toByte() }
        val partC = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) +
            ByteArray(300) { 'C'.code.toByte() }

        val dest = tempFile("merged4.mp3")
        TtsPlaybackService.mergeAudioFiles(
            listOf(
                tempFile("a2.mp3").apply { writeBytes(partA) },
                tempFile("c.mp3").apply { writeBytes(partC) },
            ),
            dest
        )

        assertEquals(
            "合并后长度应为两段之和",
            (partA.size + partC.size).toLong(),
            dest.length()
        )
    }

    @Test
    fun `声明的 data 长度不可信时回落到真实文件长度`() {
        // 下载被截断的服务商响应会声明一个超长的 data size
        val good = wavBytes(1000, withList = false)
        val corrupt = good.copyOf()
        var pos = 12
        while (pos + 8 <= corrupt.size) {
            val id = String(corrupt, pos, 4, Charsets.US_ASCII)
            val size = readInt32(corrupt, pos + 4)
            if (id == "data") {
                System.arraycopy(le32(9_999_999), 0, corrupt, pos + 4, 4)
                break
            }
            pos += 8 + size + (size and 1)
        }
        System.arraycopy(le32(9_999_999), 0, corrupt, 4, 4)

        val dest = tempFile("merged5.wav")
        TtsPlaybackService.mergeAudioFiles(
            listOf(
                tempFile("corrupt.wav").apply { writeBytes(corrupt) },
                tempFile("y.wav").apply { writeBytes(wavBytes(500, false)) },
            ),
            dest
        )

        assertEquals(3000, wavData(dest).size)
    }

    // ------------------------------------------------------------------
    // 格式探测
    // ------------------------------------------------------------------

    @Test
    fun `按文件头魔数识别音频格式`() {
        fun sniff(name: String, bytes: ByteArray): String =
            TtsPlaybackService.detectAudioExtension(tempFile(name).apply { writeBytes(bytes) })

        assertEquals("wav", sniff("a.bin", wavBytes(10, false)))
        assertEquals(
            "mp3",
            sniff("b.bin", byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(100))
        )
        assertEquals(
            "mp3",
            sniff("c.bin", "ID3".toByteArray() + ByteArray(100))
        )
        assertEquals("ogg", sniff("d.bin", "OggS".toByteArray() + ByteArray(100)))
        assertEquals("flac", sniff("e.bin", "fLaC".toByteArray() + ByteArray(100)))
        assertEquals(
            "m4a",
            sniff("f.bin", byteArrayOf(0, 0, 0, 0) + "ftyp".toByteArray() + ByteArray(100))
        )
    }

    @Test
    fun `isWavFile 只认 RIFF WAVE`() {
        assertTrue(TtsPlaybackService.isWavFile(tempFile("g.bin").apply { writeBytes(wavBytes(10, false)) }))
        assertFalse(
            TtsPlaybackService.isWavFile(
                tempFile("h.bin").apply {
                    writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(100))
                }
            )
        )
    }
}
