package com.example.myapplication111.tts

import com.example.myapplication111.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 通用 TTS 服务商（OpenAI 兼容格式），取消任何厂商的默认地位：
 * 只要填 API 地址 + API Key 就能接入，适用于绝大多数厂商：
 * - OpenAI:      https://api.openai.com/v1              +  gpt-4o-mini-tts / tts-1
 * - 硅基流动:     https://api.siliconflow.cn/v1          +  FunAudioLLM/CosyVoice2-0.5B
 * - Groq:        https://api.groq.com/openai/v1         +  playai-tts
 * - 智谱:         https://open.bigmodel.cn/api/paas/v4   +  glm-tts（音色如 tongtong）
 * - 火山方舟:     https://ark.cn-beijing.volces.com/api/v3
 *
 * 请求体：{"model": "...", "input": "...", "voice": "...", "response_format": "..."}
 * 注意各厂商支持的 response_format 不同（OpenAI 支持 mp3，智谱 GLM-TTS 只支持 wav/pcm），
 * 所以音频格式由通用服务商设置里的"格式"字段承载（此服务商不显示"语言"输入框），默认 wav。
 * 响应三种形态都支持：
 * 1. 直接返回音频二进制（Content-Type 为 audio/ 开头或 application/octet-stream）
 * 2. JSON 里带音频地址（如 {"url": "..."} / {"audio_url": "..."} / {"data": [{"url": "..."}]}）
 * 3. JSON 里带 base64 音频（如 {"audio": "base64..."} / OpenAI 的 {"data": [{"b64_json": "..."}]}）
 */
class OpenAiCompatTtsProvider : TtsProvider {

    override val id: String = SettingsRepository.PROVIDER_OPENAI_COMPAT

    override suspend fun synthesize(
        config: SettingsRepository.ProviderConfig,
        text: String
    ): TtsResult = withContext(Dispatchers.IO) {
        try {
            val apiKey = config.apiKey.trim()
            if (apiKey.isBlank()) return@withContext TtsResult(null, "请先填写 API Key")
            if (config.apiUrl.isBlank()) return@withContext TtsResult(null, "请先填写 API 地址")

            val endpoint = resolveEndpoint(config.apiUrl)
            val client = ttsHttpClient()

            // language 字段在此服务商下复用为"音频格式"（wav/mp3/pcm/flac/opus/aac）
            val format = normalizeAudioFormat(config.language)
            val jsonBody = JSONObject().apply {
                put("model", config.model.trim().ifBlank { DEFAULT_MODEL })
                put("input", TtsPlaybackService.normalizeForSynthesis(text))
                if (config.voice.isNotBlank()) put("voice", config.voice.trim())
                put("response_format", format)
                // 仅在"接口变速"模式下才带速度；为 1.0 时保持请求与原行为完全一致，
                // 避免个别厂商把未知字段当错误返回。智谱/OpenAI 的 speed 取值都是 [0.5, 2]。
                if (config.speed != 1.0f) {
                    put("speed", config.speed.coerceIn(0.5f, 2.0f).toDouble())
                }
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""
                val bodyBytes = response.body?.bytes()

                if (!response.isSuccessful) {
                    val detail = bodyBytes?.toString(Charsets.UTF_8)?.take(500) ?: ""
                    return@withContext TtsResult(
                        null,
                        "请求失败 (HTTP ${response.code}) -> $endpoint\n$detail"
                    )
                }
                if (bodyBytes == null || bodyBytes.isEmpty()) {
                    return@withContext TtsResult(
                        null,
                        "服务器返回为空 (HTTP ${response.code}) -> $endpoint"
                    )
                }

                // 形态 1：响应本身就是音频文件 -> 直接把字节带回上层，免二次下载
                if (isAudioContentType(contentType) || TtsPlaybackService.looksLikeAudioBytes(bodyBytes)) {
                    return@withContext TtsResult(
                        null, null, bodyBytes, guessAudioFormat(contentType, format)
                    )
                }

                val bodyString = bodyBytes.toString(Charsets.UTF_8)
                val json = try {
                    JSONObject(bodyString)
                } catch (e: Exception) {
                    // 不是 JSON 也不是音频 -> 无法识别
                    return@withContext TtsResult(
                        null,
                        "无法识别的响应格式 (Content-Type: $contentType) -> $endpoint: ${bodyString.take(300)}"
                    )
                }

                // 形态 2：JSON 中递归查找音频地址
                findAudioUrl(json)?.let { return@withContext TtsResult(it, null) }

                // 形态 3：JSON 中递归查找 base64 音频字段
                findBase64Audio(json)?.let { b64 ->
                    val bytes = runCatching {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        return@withContext TtsResult(null, null, bytes, format)
                    }
                }

                TtsResult(
                    null,
                    "响应中没有音频地址或音频数据 -> $endpoint: ${bodyString.take(300)}"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            TtsResult(null, e.message ?: "网络请求异常")
        }
    }

    /** 是否是音频类 Content-Type。 */
    private fun isAudioContentType(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.startsWith("audio/") ||
            ct.startsWith("application/octet-stream") ||
            ct.startsWith("binary/octet-stream") ||
            ct.startsWith("application/x-wav") ||
            ct.startsWith("application/binary") ||
            (ct.contains("audio") && (ct.contains("mpeg") || ct.contains("mp3")))
    }

    /** 根据 Content-Type 与请求格式猜测音频格式，供缓存文件命名/合并使用。 */
    private fun guessAudioFormat(contentType: String, requested: String): String {
        val ct = contentType.lowercase()
        if (ct.contains("wav") || ct.contains("wave")) return "wav"
        if (ct.contains("ogg") || ct.contains("opus")) return "ogg"
        if (ct.contains("flac")) return "flac"
        if (ct.contains("aac") || ct.contains("mp4") || ct.contains("m4a")) return "aac"
        if (ct.contains("mpeg") || ct.contains("mp3")) return "mp3"
        return requested
    }

    /** 把用户填的格式归一化为合法 response_format；语言值/空值/非法值回落 wav（兼容面最广）。 */
    private fun normalizeAudioFormat(raw: String): String {
        val v = raw.trim().lowercase()
        return when (v) {
            "mp3", "wav", "pcm", "flac", "opus", "aac" -> v
            else -> "wav" // 兼容历史遗留的 "English"/"Chinese" 等语言值
        }
    }

    /**
     * 在 JSON 里递归找一个看起来像音频 URL 的字符串值。
     * 优先匹配键名含 url/link/audio/speech 的字段；也兼容数组（如 OpenAI data[0].url）。
     */
    private fun findAudioUrl(node: Any?, depth: Int = 0): String? {
        if (depth > 6 || node == null) return null
        return when (node) {
            is JSONObject -> {
                // 先按键名优先级找
                val preferredKeys = listOf(
                    "audio_url", "audioUrl", "audio", "url", "link",
                    "speech_url", "output_url", "data"
                )
                for (key in preferredKeys) {
                    val value = node.opt(key)
                    if (value is String && looksLikeHttpUrl(value)) return value
                    val nested = findAudioUrl(value, depth + 1)
                    if (nested != null) return nested
                }
                // 兜底：遍历所有字段
                node.keys().forEach { key ->
                    if (key in preferredKeys) return@forEach
                    val nested = findAudioUrl(node.opt(key), depth + 1)
                    if (nested != null) return nested
                }
                null
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    val nested = findAudioUrl(node.opt(i), depth + 1)
                    if (nested != null) return nested
                }
                null
            }
            else -> null
        }
    }

    private fun looksLikeHttpUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    /**
     * 在 JSON 里递归找一个疑似 base64 音频的长字符串。
     * 键名优先 audio/b64_json/base64/data；长度阈值过滤掉普通字段。
     */
    private fun findBase64Audio(node: Any?, depth: Int = 0): String? {
        if (depth > 6 || node == null) return null
        return when (node) {
            is JSONObject -> {
                val preferredKeys = listOf("b64_json", "audio", "base64", "audio_base64", "data")
                for (key in preferredKeys) {
                    val value = node.opt(key)
                    if (value is String && value.length > MIN_BASE64_LENGTH) return value
                    val nested = findBase64Audio(value, depth + 1)
                    if (nested != null) return nested
                }
                null
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    val nested = findBase64Audio(node.opt(i), depth + 1)
                    if (nested != null) return nested
                }
                null
            }
            else -> null
        }
    }

    companion object {
        private const val DEFAULT_MODEL = "tts-1"
        private const val MIN_BASE64_LENGTH = 512

        /**
         * 智能补全 endpoint。用户填以下任意形式都能用：
         * - https://open.bigmodel.cn/api/paas/v4        -> 拼 /audio/speech（智谱路径）
         * - https://open.bigmodel.cn/api/               -> 拼 paas/v4/audio/speech（智谱路径）
         * - https://api.openai.com                      -> 拼 /v1/audio/speech
         * - https://api.openai.com/v1                   -> 拼 /audio/speech
         * - https://api.siliconflow.cn/v1/audio/speech  -> 直接用
         * - 其他任何完整地址                             -> 直接用
         */
        fun resolveEndpoint(apiUrl: String): String {
            val trimmed = apiUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) return trimmed

            // 已是完整 TTS 地址则直接使用
            if (trimmed.endsWith("/audio/speech") || trimmed.endsWith("/tts")) return trimmed

            // 智谱 BigModel：路径为 /api/paas/v4/audio/speech
            if (trimmed.endsWith("/paas/v4") ||
                trimmed.endsWith("/paas/v3") ||
                Regex("/paas/v\\d+$").containsMatchIn(trimmed)
            ) return "$trimmed/audio/speech"
            // 填的是 https://open.bigmodel.cn/api 或 .../api/ 的形式
            if (trimmed.endsWith("/api") && trimmed.contains("bigmodel.cn")) {
                return "$trimmed/paas/v4/audio/speech"
            }

            // OpenAI 风格：带 /v1 或 /vN 版本段 -> 拼到版本段后
            if (Regex("/v\\d+(beta|\\d*)$").containsMatchIn(trimmed) || trimmed.endsWith("/v1beta")) {
                return "$trimmed/audio/speech"
            }
            // 不带版本段 -> 默认补 /v1（OpenAI、硅基流动等的常见形态）
            return "$trimmed/v1/audio/speech"
        }
    }
}
