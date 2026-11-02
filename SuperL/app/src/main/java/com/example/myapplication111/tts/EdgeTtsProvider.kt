package com.example.myapplication111.tts

import com.example.myapplication111.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 微软 Edge 免费朗读（edge-tts 同款协议），2026-10-04 接入。
 *
 * 与其他服务商的 HTTP 接口不同，它走 WebSocket，且无需任何 Key：
 * 1. 连接 wss://speech.platform.bing.com/.../edge/v1，URL 上带 TrustedClientToken、
 *    ConnectionId、Sec-MS-GEC（防滥用 token）、Sec-MS-GEC-Version；
 * 2. 先发一条 speech.config（声明输出格式 audio-24khz-48kbitrate-mono-mp3），
 *    再发一条 ssml 消息（文本包在 <speak><voice><prosody> 里）；
 * 3. 服务端持续推二进制帧：前 2 字节是大端"头部长度"，随后是 ASCII 头
 *    （X-RequestId / Content-Type / Path:audio），其余才是音频数据；
 *    Path:turn.end 的文本消息表示本次合成结束（turn.end 可能与最后一批音频竞争，
 *    所以连接正常关闭时若已有音频也视为成功，与 edge-tts 行为一致）。
 *
 * speech.config 里开启了 wordBoundaryEnabled：服务端会随音频推 Path:audio.metadata
 * 的文本消息，带每个词的精确时间戳（100ns tick）。这是播放页逐句高光的
 * 精确数据来源 —— 不开它就只能按字数估时间，长句里高光能差好几秒。
 *
 * Sec-MS-GEC v2 算法（2026-10 微软强制升级到 v2 DRM 协议，与 edge-tts 7.3.1 逐位
 * 对拍过）：Unix 时间 + Windows 文件时间偏移，向下取整到 5 分钟窗，乘以 1e7 转成
 * 100ns tick，拼接 TrustedClientToken 后过加盐 SHA-256（前 7 字节作盐）与 CS64 两道
 * 自定义摘要，输出"<16 位大写hex>-<64 位大写hex>"。旧的 v1 纯 SHA-256 token 在升级
 * 后一律 403。手机时钟不准时同样 403——按响应头 Date 校准一次重试。
 *
 * 单条 SSML 里文本上限 4096 个 UTF-8 字节（转义后），超过时在本类内部按
 * 自然断点再切，逐条连接合成后拼接输出（mp3 帧独立，可直接拼接）。
 * 音频格式恒为 MP3；语速通过 SSML prosody rate 支持（supportsSpeed = true）。
 */
class EdgeTtsProvider : TtsProvider {

    override val id: String = SettingsRepository.PROVIDER_EDGE

    override suspend fun synthesize(
        config: SettingsRepository.ProviderConfig,
        text: String
    ): TtsResult = withContext(Dispatchers.IO) {
        val trimmed = TtsPlaybackService.normalizeForSynthesis(text)
        if (trimmed.isBlank()) return@withContext TtsResult(null, "文本为空")

        val voice = normalizeVoice(config.voice)
            ?: return@withContext TtsResult(
                null,
                "音色名格式不对：应为 en-US-EmmaMultilingualNeural 这类" +
                    "（语言-地区-名字Neural），留空则用默认音色 $DEFAULT_VOICE"
            )
        val rate = toRateParam(config.speed)
        val client = ttsHttpClient()

        try {
            // 先清洗、转义，再按转义后的 UTF-8 字节切，保证任何一段都不超服务端上限
            val escaped = escapeXmlText(cleanIncompatibleCharacters(trimmed))
            val chunks = splitByEscapedByteLimit(escaped, MAX_SSML_TEXT_BYTES)
            val merged = ByteArrayOutputStream()
            val wordTimings = mutableListOf<WordTiming>()
            var audioBytesSoFar = 0L

            for (chunk in chunks) {
                if (chunk.isBlank()) continue
                var skewSeconds = 0L
                var audio: ByteArray? = null
                var roundWords: List<WordTiming> = emptyList()
                var errorMessage: String? = null

                // 至多两轮：第一轮 403 且带服务器时间时按它校准时钟偏移再试一次
                for (attempt in 0 until 2) {
                val outcome = withTimeoutOrNull(ROUND_TIMEOUT_MS) {
                    synthesizeRound(client, voice, rate, chunk, skewSeconds)
                }
                if (outcome == null) {
                    // 超时：连接已随取消关闭，换新连接再试一次
                    errorMessage = "Edge TTS 合成超时，请检查网络后重试"
                    continue
                }
                if (outcome.audio != null) {
                    audio = outcome.audio
                    roundWords = outcome.words
                    break
                }
                    val canCalibrate = attempt == 0 &&
                        (outcome.httpCode == 401 || outcome.httpCode == 403) &&
                        outcome.serverDateEpochSeconds > 0
                    if (canCalibrate) {
                        skewSeconds = outcome.serverDateEpochSeconds -
                            System.currentTimeMillis() / 1000
                    } else {
                        errorMessage = outcome.error ?: "Edge TTS 请求失败"
                        break
                    }
                }

                if (audio == null) {
                    return@withContext TtsResult(null, errorMessage ?: "Edge TTS 请求失败")
                }
                // WordBoundary 的 Offset 相对"本轮音频"的开头；一个文本段内部再切成
                // 多轮时，把前面各轮的音频按 CBR 字节量折算成毫秒累出基准偏移，
                // 统一换到"整段音频"的坐标系。48kbps CBR 恒为 6 字节/毫秒，
                // 与 SSML prosody rate 无关（变速只改样本数，不改码率）。
                val baseMs = audioBytesSoFar * 8 / AUDIO_BITRATE_BPS
                for (word in roundWords) {
                    wordTimings.add(WordTiming(word.offsetMs + baseMs, word.durationMs, word.text))
                }
                audioBytesSoFar += audio.size.toLong()
                merged.write(audio)
            }

            val bytes = merged.toByteArray()
            if (bytes.isEmpty()) {
                TtsResult(null, "Edge TTS 未返回音频数据")
            } else {
                TtsResult(null, null, bytes, AUDIO_FORMAT, wordTimings)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            TtsResult(null, e.message ?: "网络请求异常")
        }
    }

    // ------------------------------------------------------------------
    // 单轮合成：一条 WebSocket 连接完成 speech.config + ssml + 收音频
    // ------------------------------------------------------------------

    /** 单轮合成结果：audio 非空即成功；失败时带 HTTP 码与服务器时间（供时钟校准重试）。 */
    private class RoundOutcome(
        val audio: ByteArray?,
        val words: List<WordTiming>,
        val httpCode: Int,
        val serverDateEpochSeconds: Long,
        val error: String?
    )

    private suspend fun synthesizeRound(
        client: OkHttpClient,
        voiceName: String,
        rate: String,
        escapedText: String,
        skewSeconds: Long
    ): RoundOutcome {
        val gec = secMsGec(System.currentTimeMillis() / 1000, skewSeconds)
        val url = "$WSS_URL&ConnectionId=${randomHex32()}" +
            "&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val request = Request.Builder()
            .url(url)
            .headers(roundHeaders())
            .build()

        return suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            val audioBuffer = ByteArrayOutputStream()
            val wordBuffer = mutableListOf<WordTiming>()

            fun finish(outcome: RoundOutcome) {
                // 双重保险：Continuation 只能恢复一次；已被取消的恢复是空操作
                if (resumed.compareAndSet(false, true)) {
                    cont.resume(outcome)
                }
            }

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val timestamp = nowTimestamp()
                    webSocket.send(speechConfigMessage(timestamp))
                    webSocket.send(ssmlMessage(timestamp, voiceName, rate, escapedText))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (messagePath(text)) {
                        "turn.end" -> {
                            val audio = audioBuffer.toByteArray()
                            webSocket.close(1000, null)
                            finish(
                                if (audio.isEmpty()) {
                                    RoundOutcome(
                                        null, emptyList(), 0, 0,
                                        "服务端未返回音频数据（音色名可能无效）"
                                    )
                                } else {
                                    RoundOutcome(audio, wordBuffer.toList(), 0, 0, null)
                                }
                            )
                        }
                        // 逐词时间戳：正是"字幕跟着音频走"需要的精确数据
                        "audio.metadata" -> {
                            val bodyStart = text.indexOf("\r\n\r\n")
                            if (bodyStart >= 0) {
                                parseWordBoundaries(text.substring(bodyStart + 4), wordBuffer)
                            }
                        }
                        // turn.start / response 等对取音频无用，忽略
                        else -> {}
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size < 2) return
                    // 前 2 字节 = 头部块的大端长度；头块之后紧跟音频载荷
                    val headerLength =
                        ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (2 + headerLength > bytes.size) return
                    val header = bytes.substring(2, 2 + headerLength).utf8()
                    val path = header.split("\r\n")
                        .firstOrNull { it.startsWith("Path:") }
                        ?.removePrefix("Path:")
                    if (path != "audio") return
                    val payload = bytes.substring(2 + headerLength)
                    if (payload.size > 0) {
                        audioBuffer.write(payload.toByteArray())
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val detail = t.message ?: t.javaClass.simpleName
                    finish(
                        RoundOutcome(
                            null,
                            emptyList(),
                            code,
                            parseRfc1123Date(response?.header("Date")),
                            "连接失败 (HTTP $code)：$detail"
                        )
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // 服务端正常关闭：若音频已收齐（turn.end 与收尾帧竞争）也算成功
                    val audio = audioBuffer.toByteArray()
                    finish(
                        if (audio.isEmpty()) {
                            RoundOutcome(null, emptyList(), 0, 0, "连接已关闭且未收到音频")
                        } else {
                            RoundOutcome(audio, wordBuffer.toList(), 0, 0, null)
                        }
                    )
                }
            })

            cont.invokeOnCancellation { ws.cancel() }
        }
    }

    // ------------------------------------------------------------------
    // 协议常量与纯函数（JVM 单测覆盖，见 EdgeTtsProviderTest）
    // ------------------------------------------------------------------

    companion object {
        private const val AUDIO_FORMAT = "mp3"

        /**
         * 输出音频的恒定码率（48kbps CBR）。一个文本段切成多轮合成时，
         * 用它把前面各轮的音频字节数折算成毫秒，作为后续轮次词级
         * 时间戳的基准偏移。变速（prosody rate）不改变码率，折算恒成立。
         */
        private const val AUDIO_BITRATE_BPS = 48_000L
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

        /** 微软会不定期升级校验；若开始大面积 403，需随 edge-tts 版本更新这两个值。 */
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"

        /** 前缀 "2-" 表示 v2 DRM 协议（2026-10 起强制）；v1 的 "1-" 已被服务端拒绝。 */
        private const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

        /** SSML 内文本的 UTF-8 字节上限（转义后），edge-tts 同款。 */
        const val MAX_SSML_TEXT_BYTES = 4096

        private const val DEFAULT_VOICE = "en-US-EmmaMultilingualNeural"
        private const val DEFAULT_VOICE_FULL =
            "Microsoft Server Speech Text to Speech Voice (en-US, EmmaMultilingualNeural)"

        /** 单轮合成的总超时。4096 字节约等于 40 秒音频，正常网络下远用不满。 */
        private const val ROUND_TIMEOUT_MS = 90_000L
        private const val WIN_EPOCH_SECONDS = 11644473600L
        private const val TOKEN_BUCKET_SECONDS = 300L
        private const val TICKS_PER_SECOND = 10_000_000L

        /**
         * Sec-MS-GEC v2 防滥用 token（2026-10 起微软强制，旧 v1 纯 SHA-256 一律 403）。
         *
         * ticks 构造与 v1 相同，全整数运算（已验证与 edge-tts 的浮点路径在
         * 2023–2033 年逐秒一致）；随后把 "<ticks><token>" 喂给加盐 SHA-256 与
         * CS64 两道自定义摘要，输出"<16 位大写hex>-<64 位大写hex>"。
         */
        internal fun secMsGec(unixSeconds: Long, skewSeconds: Long): String {
            var ticks = unixSeconds + skewSeconds + WIN_EPOCH_SECONDS
            ticks -= ticks % TOKEN_BUCKET_SECONDS
            ticks *= TICKS_PER_SECOND
            val data = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
            val digest = saltedSha256(data)
            val (cs64First, cs64Second) = cs64Digest(data, digest)
            return u32HexLe(cs64First) + u32HexLe(cs64Second) + "-" + hexUpper(digest)
        }

        // --------------------------------------------------------------
        // Sec-MS-GEC v2 的两道内部摘要：加盐 SHA-256 + CS64。
        // 忠实移植 edge-tts 7.3.1 的 drm.py（结构有专利保护，不能化简），
        // 期望值由该实现实算生成并锁进单元测试，改任何常数都会立刻对不上。
        // --------------------------------------------------------------

        private const val U32 = 0xFFFFFFFFL

        /** 标准 SHA-256 初始向量（盐块压缩完成后加回）。 */
        private val SHA256_IV = longArrayOf(
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
        )

        /** 盐块恢复路径的起始工作状态：前 14 轮（消息字全为 0）已预先算死在里面。 */
        private val SHA256_SALT_STATE = longArrayOf(
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
        )

        /** 盐块消息字 16–29 的预计算值（其余字节的隐藏常数都埋在这里）。 */
        private val SHA256_SALT_SCHEDULE = longArrayOf(
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L
        )

        /** 标准 SHA-256 轮常数 K。 */
        private val SHA256_ROUND_CONSTANTS = longArrayOf(
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
        )

        /** 盐块恢复路径（从第 14 轮续跑）把 K[14] 换成这个修正值。 */
        private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
        private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
        private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

        private val SHA256_SALT_ROUND_CONSTANTS: LongArray by lazy {
            SHA256_ROUND_CONSTANTS.copyOf().also { it[14] = SHA256_ADJUSTMENT }
        }

        private val CS64_COEFFICIENTS = arrayOf(
            0xE82E91B1L to 0xB32700D0L, 0x44DE24C6L to 0x88DFF51DL,
            0x3BEB65BCL to 0x29514038L, 0xC89C5933L to 0xB328CBDFL,
            0x8D1EC211L to 0xD405B4D5L, 0x314EF5D5L to 0x26E55F3CL,
            0xE92491B1L to 0x607801C6L, 0xF0A5B9D5L to 0x82B2C1FBL,
            0x06064CF3L to 0xFA51A3DAL, 0xDA79FA60L to 0x207CC464L,
            0xBED20560L to 0x3C3C74F3L, 0x89F9C9F8L to 0xD2D14B61L,
            0x35FA38C6L to 0xBB04A2C1L, 0xCB49E3C6L to 0xC5626167L
        )
        private const val CS64_FACTOR = 0x82893933L
        private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
        private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

        /** 32 位循环右移（入参必须是已掩码的 32 位值）。 */
        private fun rotr32(x: Long, n: Int): Long =
            ((x ushr n) or (x shl (32 - n))) and U32

        private fun rol16(x: Long): Long = ((x shl 16) or (x ushr 16)) and U32

        /** SHA-256 small sigma0/1：合并移位后一次截断（与参考实现写法一致）。 */
        private fun sigma0(value: Long): Long =
            ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor
                (value shl 14) xor (value ushr 3)) and U32

        private fun sigma1(value: Long): Long =
            ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor
                (value shl 13) xor (value ushr 10)) and U32

        /** 64 轮压缩；[firstRound] 允许从中间轮次续跑（盐块路径从第 14 轮开始）。 */
        private fun sha256Rounds(
            state: LongArray,
            words: LongArray,
            constants: LongArray,
            firstRound: Int
        ): LongArray {
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            for (i in firstRound until 64) {
                val t1 = h +
                    (rotr32(e, 6) xor rotr32(e, 11) xor rotr32(e, 25)) +
                    (g xor (e and (f xor g))) +
                    constants[i] + words[i]
                val t2 = (rotr32(a, 2) xor rotr32(a, 13) xor rotr32(a, 22)) +
                    ((a and b) or (c and (a or b)))
                h = g; g = f; f = e
                e = (d + t1) and U32
                d = c; c = b; b = a
                a = (t1 + t2) and U32
            }
            return longArrayOf(a, b, c, d, e, f, g, h)
        }

        /** 标准消息调度扩展：words[from..63] 就地补全。 */
        private fun expandSchedule(words: LongArray, from: Int) {
            for (index in from until 64) {
                val x = words[index - 2]
                val y = words[index - 15]
                words[index] = (sigma1(x) + words[index - 7] + sigma0(y) + words[index - 16]) and U32
            }
        }

        /**
         * 由盐（数据前 7 字节）推出整套初始工作状态：盐块的消息字 16–29 直接用
         * 预计算常数加上对盐字的 sigma 修正，第 30 字带 K[14] 修正，其余照常扩展，
         * 最后从第 14 轮跑完压缩并加回标准 IV。
         */
        private fun saltedInitial(salt: ByteArray): LongArray {
            val words = LongArray(64)
            words[14] = (salt[0].toLong() and 0xFF shl 16) or
                (salt[1].toLong() and 0xFF shl 8) or (salt[2].toLong() and 0xFF)
            words[15] = (salt[3].toLong() and 0xFF shl 24) or (salt[4].toLong() and 0xFF shl 16) or
                (salt[5].toLong() and 0xFF shl 8) or (salt[6].toLong() and 0xFF)
            for (index in 16 until 30) {
                var value = SHA256_SALT_SCHEDULE[index - 16]
                value += if (index == 16) {
                    sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    sigma1(words[index - 2])
                }
                if (index - 7 >= 14) value += words[index - 7]
                if (index - 15 >= 14) {
                    value += if (index == 29) {
                        sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                    } else {
                        sigma0(words[index - 15])
                    }
                }
                words[index] = value and U32
            }
            words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]) and U32
            expandSchedule(words, 31)
            val working = sha256Rounds(SHA256_SALT_STATE, words, SHA256_SALT_ROUND_CONSTANTS, 14)
            return LongArray(8) { (SHA256_IV[it] + working[it]) and U32 }
        }

        /**
         * v2 的加盐 SHA-256：前 7 字节不进消息块，而是通过 [saltedInitial] 决定
         * 初始状态；其余字节分块正常压缩，长度字段按"盐块算整 64 字节"计。
         */
        private fun saltedSha256(data: ByteArray): ByteArray {
            require(data.size >= 7) { "v2 token 盐至少 7 字节" }
            val bitLength = (57 + data.size) * 8L
            val zeros = Math.floorMod(55 - (57 + data.size), 64)
            val padded = ByteArray(data.size - 7 + 1 + zeros + 8)
            data.copyInto(padded, 0, 7, data.size)
            padded[data.size - 7] = 0x80.toByte()
            for (i in 0 until 8) {
                padded[padded.size - 8 + i] = (bitLength ushr (56 - 8 * i)).toByte()
            }
            var state = saltedInitial(data)
            for (offset in 0 until padded.size step 64) {
                // 调度扩展是就地写满 64 个字，数组必须预留到 64（Python 列表可增长，这里不行）
                val words = LongArray(64)
                for (w in 0 until 16) {
                    val o = offset + 4 * w
                    words[w] = (padded[o].toLong() and 0xFF shl 24) or (padded[o + 1].toLong() and 0xFF shl 16) or
                        (padded[o + 2].toLong() and 0xFF shl 8) or (padded[o + 3].toLong() and 0xFF)
                }
                expandSchedule(words, 16)
                val working = sha256Rounds(state, words, SHA256_ROUND_CONSTANTS, 0)
                for (i in 0 until 8) state[i] = (state[i] + working[i]) and U32
            }
            return ByteArray(32) { i ->
                ((state[i / 4] ushr (24 - 8 * (i % 4))) and 0xFF).toByte()
            }
        }

        /** CS64：以摘要前 8 字节（小端）为密钥的两支 32 位滚动累加器。 */
        private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
            val keyA = le32(digest, 0) or 1L
            val keyB = le32(digest, 4) or 1L
            val factorA = keyA * CS64_FACTOR and U32
            val factorB = keyB * CS64_FACTOR and U32

            // 14 个系数项 + 数据字项，Python 侧是边算边 append，这里需一次分配到位
            val dataWords = wordsLe4Even(byteArrayOf(0) + data)
            val terms = LongArray(CS64_COEFFICIENTS.size + dataWords.size)
            val offsets = LongArray(terms.size)
            for ((index, coefficient) in CS64_COEFFICIENTS.withIndex()) {
                terms[index] = coefficient.first *
                    (if (index % 2 == 0) factorA else factorB) and U32
                offsets[index] = coefficient.second
            }
            for ((index, word) in dataWords.withIndex()) {
                var term = word * (if (index % 2 == 0) keyA else keyB) and U32
                var offset = rol16(word) *
                    (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL) and U32
                if (index == 0) {
                    term += CS64_ADJUSTMENT0 * factorA and U32
                    offset += CS64_ADJUSTMENT1
                }
                terms[index + CS64_COEFFICIENTS.size] = term and U32
                offsets[index + CS64_COEFFICIENTS.size] = offset and U32
            }

            var swapAcc = 0L
            var swapLast = 0L
            var state = 0L
            var acc = 0L
            for (index in terms.indices step 2) {
                val x = terms[index]
                val y = terms[index + 1]

                var t = rol16(offsets[index] + x and U32)
                var q = t * 0x76F50F34L and U32
                swapAcc += (rol16(q) * 0xC3EFEA97L + q) and U32

                t = rol16(offsets[index + 1] + y and U32)
                q = t * 0x1881CE3CL and U32
                swapLast = (rol16(q) * 0x35BD1EC9L + q) and U32
                swapAcc = (swapAcc + swapLast) and U32

                state = (state * keyA + x) and U32
                state = rol16(state) * 0xCF98B111L and U32
                state = rol16(state) * 0x87085B9FL and U32
                state = rol16(state) * 0x12CEB96DL and U32
                state = rol16(state) * 0x257E1D83L and U32
                acc += state
                t = (state * keyB + y) and U32
                t = rol16(t) * 0xA27416F5L and U32
                t = rol16(t) * 0xD38396FFL and U32
                state = rol16(rol16(t) * 0x7C932B89L and U32) * 0xBFA49F69L and U32
                acc = (acc + state) and U32
            }
            return Pair(swapLast xor state, swapAcc xor acc)
        }

        /** 小端 4 字节切成 32 位字并补齐成对（不足处补零字），与参考实现一致。 */
        private fun wordsLe4Even(data: ByteArray): LongArray {
            val padded = data + ByteArray(Math.floorMod(-data.size, 4))
            val words = LongArray(padded.size / 4) { i ->
                val o = 4 * i
                (padded[o].toLong() and 0xFF) or (padded[o + 1].toLong() and 0xFF shl 8) or
                    (padded[o + 2].toLong() and 0xFF shl 16) or (padded[o + 3].toLong() and 0xFF shl 24)
            }
            return if (words.size % 2 == 1) words + 0L else words
        }

        private fun le32(b: ByteArray, offset: Int): Long =
            (b[offset].toLong() and 0xFF) or (b[offset + 1].toLong() and 0xFF shl 8) or
                (b[offset + 2].toLong() and 0xFF shl 16) or (b[offset + 3].toLong() and 0xFF shl 24)

        private fun u32HexLe(value: Long): String = byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        ).joinToString("") { "%02X".format(it) }

        private fun hexUpper(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }


        /** 语速倍率转 SSML prosody rate 百分比：1.0 -> "+0%"，0.5 -> "-50%"，2.0 -> "+100%"。 */
        internal fun toRateParam(speed: Float): String {
            val percent = (speed.coerceIn(0.5f, 2.0f) - 1f) * 100f
            val rounded = percent.roundToInt()
            return if (rounded >= 0) "+$rounded%" else "$rounded%"
        }

        /** 去掉服务不接受的控制字符（0-8、11-12、14-31，含垂直制表符），替换为空格。 */
        internal fun cleanIncompatibleCharacters(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                val code = ch.code
                sb.append(if (code <= 8 || code in 11..12 || code in 14..31) ' ' else ch)
            }
            return sb.toString()
        }

        /**
         * XML 转义。只转义 & < > 三个：文本位于元素内容里（不在属性里），
         * 引号无需转义——这一点与 edge-tts 一致，避免英文里大量的撇号
         * （don't -> don&apos;t）白白膨胀字节数、挤占 4096 字节预算。
         */
        internal fun escapeXmlText(text: String): String =
            text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

        /**
         * 把（已转义的）文本按 UTF-8 字节数切成不超过 [limit] 的段。
         * 优先在空格/换行处断开（断点处的空白留在前一段，跨段单词不粘连）；
         * 不会切进 &amp; 之类的转义实体，也不会切开代理对。
         */
        internal fun splitByEscapedByteLimit(text: String, limit: Int): List<String> {
            if (limit <= 0) return listOf(text)
            if (text.toByteArray(Charsets.UTF_8).size <= limit) return listOf(text)

            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var bytes = 0
                var lastBreak = -1   // 相对 start 的位置：最后一个空格/换行之后
                var lastAmp = -1     // 最后一个未闭合 & 的位置（其后出现 ; 则复位）
                var i = start
                while (i < text.length) {
                    val ch = text[i]
                    val paired = ch.isHighSurrogate() &&
                        i + 1 < text.length && text[i + 1].isLowSurrogate()
                    val chBytes = if (paired) 4 else utf8Length(ch)
                    if (bytes + chBytes > limit) break
                    i += if (paired) 2 else 1
                    bytes += chBytes
                    if (ch == ' ' || ch == '\n') lastBreak = i - start
                    if (ch == '&') lastAmp = i - 1 - start
                    if (ch == ';') lastAmp = -1
                }
                var cut = i - start
                if (lastBreak > 0) cut = lastBreak
                // 实体会横跨窗口边界：把断点退回到 & 之前，实体完整地留给下一段
                if (lastAmp >= 0 && lastAmp < cut && lastAmp + start > start) cut = lastAmp
                if (cut <= 0) {
                    // 单个字符就超限（正常参数下不可能）：按 UTF-16 消费一个码点
                    cut = if (text[start].isHighSurrogate() &&
                        start + 1 < text.length && text[start + 1].isLowSurrogate()
                    ) 2 else 1
                }

                val chunk = text.substring(start, start + cut)
                if (chunk.isNotBlank()) chunks.add(chunk)
                start += cut
            }
            return chunks
        }

        private fun utf8Length(ch: Char): Int = when {
            ch.code < 0x80 -> 1
            ch.code < 0x800 -> 2
            else -> 3
        }

        /**
         * 音色名归一化：空白回落默认音色；已是完整形式则原样；
         * "en-US-EmmaMultilingualNeural" 或 "zh-CN-liaoning-XiaobeiNeural" 转成
         * "Microsoft Server Speech Text to Speech Voice (语言-地区, 名字)"。
         * 规则与 edge-tts 的 TTSConfig 一致。
         */
        internal fun normalizeVoice(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return DEFAULT_VOICE_FULL
            if (trimmed.startsWith("Microsoft Server Speech")) return trimmed
            val match = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").matchEntire(trimmed)
                ?: return null
            var region = match.groupValues[2]
            var name = match.groupValues[3]
            val dash = name.indexOf('-')
            if (dash >= 0) {
                region = "$region-${name.substring(0, dash)}"
                name = name.substring(dash + 1)
            }
            return "Microsoft Server Speech Text to Speech Voice " +
                "(${match.groupValues[1]}-$region, $name)"
        }

        // ------ 消息构造 ------

        /** Javascript 风格时间戳，如 "Sat Oct 04 2026 08:53:00 GMT+0000 (Coordinated Universal Time)"。 */
        private fun nowTimestamp(): String =
            SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US
            ).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

        private fun speechConfigMessage(timestamp: String): String =
            "X-Timestamp:$timestamp\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

        /** 注意 X-Timestamp 结尾多一个字面量 Z——微软的怪癖，必须原样保留。 */
        private fun ssmlMessage(
            timestamp: String,
            voiceName: String,
            rate: String,
            escapedText: String
        ): String =
            "X-RequestId:${randomHex32()}\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${timestamp}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voiceName'>" +
                "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>$escapedText</prosody>" +
                "</voice></speak>"

        private fun roundHeaders(): Headers = Headers.Builder()
            .add("Pragma", "no-cache")
            .add("Cache-Control", "no-cache")
            .add("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .add("User-Agent", userAgent())
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Cookie", "muid=${randomHex32().uppercase()};")
            .build()

        private fun userAgent(): String {
            val major = CHROMIUM_FULL_VERSION.substringBefore('.')
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
        }

        /**
     * 解析 audio.metadata 消息体，取出 WordBoundary 词级时间戳（100ns tick → 毫秒）。
     * 一条消息可能带多个元数据项，只认 WordBoundary，其余（如 SentenceBoundary）忽略。
     */
    internal fun parseWordBoundaries(body: String, out: MutableList<WordTiming>) {
        val entries = try {
            org.json.JSONObject(body).optJSONArray("Metadata")
        } catch (e: Exception) {
            null
        } ?: return
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("Type") != "WordBoundary") continue
            val data = entry.optJSONObject("Data") ?: continue
            val word = data.optJSONObject("text")?.optString("Data") ?: continue
            if (word.isBlank()) continue
            out.add(
                WordTiming(
                    offsetMs = data.optLong("Offset") / 10_000L,
                    durationMs = data.optLong("Duration") / 10_000L,
                    text = word
                )
            )
        }
    }

    private fun messagePath(message: String): String? {
            val headEnd = message.indexOf("\r\n\r\n")
            val head = if (headEnd >= 0) message.substring(0, headEnd) else message
            return head.split("\r\n")
                .firstOrNull { it.startsWith("Path:") }
                ?.removePrefix("Path:")
        }

        private fun parseRfc1123Date(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return try {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time?.let { it / 1000 } ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        private fun randomHex32(): String =
            UUID.randomUUID().toString().replace("-", "")
    }
}
