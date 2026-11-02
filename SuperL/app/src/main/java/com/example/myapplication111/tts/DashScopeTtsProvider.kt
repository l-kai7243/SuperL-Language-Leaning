package com.example.myapplication111.tts

import com.example.myapplication111.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 阿里云百炼（DashScope）千问 TTS。
 *
 * 请求体：model + input.text + input.voice + input.language_type。
 * 短文本同步返回 output.audio.url；长文本可能返回 output.task_id，
 * 需要轮询任务状态（最多 30 次，每次 2 秒）直到拿到音频 URL。
 */
class DashScopeTtsProvider : TtsProvider {

    override val id: String = SettingsRepository.PROVIDER_DASHSCOPE

    override suspend fun synthesize(
        config: SettingsRepository.ProviderConfig,
        text: String
    ): TtsResult = withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = resolveEndpoint(config.apiUrl)
            val client = ttsHttpClient()

            val jsonBody = JSONObject().apply {
                put("model", config.model)
                put("input", JSONObject().apply {
                    put("text", text)
                    put("voice", config.voice)
                    put("language_type", config.language.ifBlank { "English" })
                })
            }

            val request = Request.Builder()
                .url(resolvedUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (bodyString.isBlank()) {
                    return@withContext TtsResult(null, "服务器返回为空 (HTTP ${response.code})")
                }

                val json = try {
                    JSONObject(bodyString)
                } catch (e: Exception) {
                    return@withContext TtsResult(
                        null,
                        "无法解析服务器响应 (HTTP ${response.code}): ${bodyString.take(500)}"
                    )
                }

                // 同步模式：output.audio.url
                val audioUrl = json.optJSONObject("output")
                    ?.optJSONObject("audio")
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() }
                if (audioUrl != null) return@withContext TtsResult(audioUrl, null)

                // 异步模式：output.task_id -> 轮询
                val taskId = json.optJSONObject("output")
                    ?.optString("task_id")
                    ?.takeIf { it.isNotBlank() }
                if (taskId != null) {
                    return@withContext pollTaskStatus(client, config.apiKey, resolvedUrl, taskId)
                }

                TtsResult(null, "未找到音频地址或任务ID (HTTP ${response.code}): ${bodyString.take(500)}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            TtsResult(null, e.message ?: "网络请求异常")
        }
    }

    private suspend fun pollTaskStatus(
        client: OkHttpClient,
        apiKey: String,
        baseUrl: String,
        taskId: String
    ): TtsResult = withContext(Dispatchers.IO) {
        val maxRetries = 30
        val delayMillis = 2000L
        val queryUrl = "$baseUrl?task_id=$taskId"

        repeat(maxRetries) {
            try {
                delay(delayMillis)
                val request = Request.Builder()
                    .url(queryUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isBlank()) return@repeat
                    val json = try {
                        JSONObject(bodyString)
                    } catch (e: Exception) {
                        return@repeat
                    }

                    when (json.optJSONObject("output")?.optString("task_status")) {
                        "SUCCEEDED", "COMPLETED" -> {
                            val audioUrl = json.optJSONObject("output")
                                ?.optJSONObject("audio")
                                ?.optString("url")
                                ?.takeIf { it.isNotBlank() }
                            if (audioUrl != null) return@withContext TtsResult(audioUrl, null)
                        }
                        "FAILED" -> {
                            val errorMsg = json.optString("message", "任务失败")
                            return@withContext TtsResult(null, "任务失败: $errorMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        TtsResult(null, "任务处理超时（已轮询 $maxRetries 次）")
    }

    companion object {
        private const val DEFAULT_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

        /**
         * 智能补全 endpoint：
         * - 为空 -> 默认地址
         * - 已包含 "multimodal-generation" -> 直接使用
         * - 以 "/api/v1" 结尾 -> 拼接完整路径
         * - 其他 -> 默认地址
         */
        fun resolveEndpoint(apiUrl: String): String {
            val trimmed = apiUrl.trim()
            if (trimmed.isBlank()) return DEFAULT_URL
            if (trimmed.contains("multimodal-generation")) return trimmed
            val noTrailingSlash = trimmed.trimEnd('/')
            return if (noTrailingSlash.endsWith("/api/v1")) {
                "$noTrailingSlash/services/aigc/multimodal-generation/generation"
            } else {
                DEFAULT_URL
            }
        }
    }
}
