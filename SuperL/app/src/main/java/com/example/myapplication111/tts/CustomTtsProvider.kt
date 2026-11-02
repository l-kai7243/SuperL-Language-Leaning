package com.example.myapplication111.tts

import com.example.myapplication111.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义 TTS 服务商（高级入口）。
 *
 * 用户在设置页提供：
 * - 请求 URL（{{apiKey}} 等占位符也会被替换，方便把 key 放在 query 里的服务）
 * - 请求头：每行一个 "Key: Value"，例如 "Authorization: Bearer {{apiKey}}"
 * - 请求体模板：任意 JSON 字符串，占位符 {{text}} {{model}} {{voice}} {{language}} {{apiKey}}
 *   会被实际值替换（JSON 安全转义）
 * - 响应提取路径：点号分隔的字段路径，如 "output.audio.url" 或 "data[0].url"，
 *   指向响应 JSON 中音频 URL 所在位置
 *
 * 这样就能不改代码接入大多数返回音频 URL 的 TTS HTTP 接口。
 */
class CustomTtsProvider : TtsProvider {

    override val id: String = SettingsRepository.PROVIDER_CUSTOM

    override suspend fun synthesize(
        config: SettingsRepository.ProviderConfig,
        text: String
    ): TtsResult = withContext(Dispatchers.IO) {
        try {
            if (config.apiUrl.isBlank()) return@withContext TtsResult(null, "请填写请求 URL")
            if (config.customBodyTemplate.isBlank()) return@withContext TtsResult(null, "请填写请求体模板")
            if (config.customResponsePath.isBlank()) return@withContext TtsResult(null, "请填写响应提取路径")

            val client = ttsHttpClient()
            val normalizedText = TtsPlaybackService.normalizeForSynthesis(text)
            val replacedUrl = replacePlaceholders(config.apiUrl, config, normalizedText, jsonEscape = false)
            val bodyString = replacePlaceholders(config.customBodyTemplate, config, normalizedText, jsonEscape = true)

            val requestBuilder = Request.Builder()
                .url(replacedUrl)
                .post(bodyString.toRequestBody(guessMediaType(bodyString)))

            // 解析用户自定义请求头，每行 "Key: Value"
            var hasContentType = false
            config.customHeaders.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val idx = trimmed.indexOf(':')
                if (idx <= 0) return@forEach
                val key = trimmed.substring(0, idx).trim()
                val value = replacePlaceholders(trimmed.substring(idx + 1).trim(), config, text, jsonEscape = false)
                if (key.equals("Content-Type", ignoreCase = true)) hasContentType = true
                requestBuilder.addHeader(key, value)
            }
            if (!hasContentType) {
                requestBuilder.addHeader("Content-Type", "application/json")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    return@withContext TtsResult(null, "服务器返回为空 (HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    return@withContext TtsResult(
                        null,
                        "请求失败 (HTTP ${response.code}): ${responseBody.take(500)}"
                    )
                }

                val url = extractByPath(responseBody, config.customResponsePath)
                if (url != null) {
                    TtsResult(url, null)
                } else {
                    TtsResult(
                        null,
                        "按路径 \"${config.customResponsePath}\" 未找到音频地址，原始返回: ${responseBody.take(500)}"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            TtsResult(null, e.message ?: "网络请求异常")
        }
    }

    /** 简单判断请求体是 JSON 还是表单/纯文本。 */
    private fun guessMediaType(body: String) =
        if (body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
            "application/json".toMediaType()
        else
            "text/plain; charset=utf-8".toMediaType()

    /**
     * 替换模板中的占位符。jsonEscape = true 时把值转义为可嵌入 JSON 字符串的内容
     * （不加外层引号，假设模板里占位符已带引号，如 {"text": "{{text}}"}）。
     */
    private fun replacePlaceholders(
        template: String,
        config: SettingsRepository.ProviderConfig,
        text: String,
        jsonEscape: Boolean
    ): String {
        fun esc(value: String): String = if (jsonEscape) jsonEscape(value) else value
        return template
            .replace("{{text}}", esc(text))
            .replace("{{model}}", esc(config.model))
            .replace("{{voice}}", esc(config.voice))
            .replace("{{language}}", esc(config.language))
            .replace("{{apiKey}}", esc(config.apiKey))
            // {{speed}} 不加引号地插入数字，方便写成 "speed": {{speed}}
            .replace("{{speed}}", formatSpeed(config.speed))
    }

    /** 语速格式化成不带多余小数位的字符串，如 1 / 1.25 / 0.8。 */
    private fun formatSpeed(speed: Float): String {
        val clamped = speed.coerceIn(SettingsRepository.MIN_SPEED, SettingsRepository.MAX_SPEED)
        return if (clamped == clamped.toInt().toFloat()) {
            clamped.toInt().toString()
        } else {
            clamped.toString()
        }
    }

    private fun jsonEscape(value: String): String {
        val quoted = JSONObject.quote(value)
        // JSONObject.quote 返回带引号的字符串，去掉首尾引号方便嵌入模板
        return if (quoted.length >= 2) quoted.substring(1, quoted.length - 1) else value
    }

    /**
     * 按点号路径从 JSON 字符串中提取字符串值，支持数组下标，如 "data[0].url"。
     */
    internal fun extractByPath(jsonString: String, path: String): String? {
        return try {
            var current: Any = JSONObject(jsonString)
            for (segment in path.split('.')) {
                if (segment.isBlank()) return null
                val name = segment.substringBefore('[')
                val indices = Regex("\\[(\\d+)]").findAll(segment).map { it.groupValues[1].toInt() }.toList()
                if (name.isNotEmpty()) {
                    current = (current as? JSONObject)?.opt(name) ?: return null
                }
                for (index in indices) {
                    val arr = current as? JSONArray ?: return null
                    if (index >= arr.length()) return null
                    current = arr.get(index)
                }
            }
            (current as? String)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
