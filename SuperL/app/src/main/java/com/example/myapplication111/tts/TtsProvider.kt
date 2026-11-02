package com.example.myapplication111.tts

import com.example.myapplication111.data.SettingsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** TTS 返回的一个词级时间戳：该词在本次合成音频里的位置与时长（毫秒）。 */
data class WordTiming(val offsetMs: Long, val durationMs: Long, val text: String)

/** TTS 调用结果。优先级：audioBytes（响应直接是音频）> audioUrl（需再下载）；都为空则看 errorMessage。 */
data class TtsResult(
    val audioUrl: String? = null,
    val errorMessage: String? = null,
    /** 服务商直接返回的音频字节（如 OpenAI /audio/speech 二进制响应），非空时无需再下载。 */
    val audioBytes: ByteArray? = null,
    /** 音频格式（mp3 / wav / ogg...），audioBytes 非空时用于缓存文件命名与合并。 */
    val audioFormat: String = "wav",
    /**
     * 词级时间戳（相对本次返回音频的开头），服务商给不了时为空列表，
     * 调用方退回按字数估算。音频内容与是否请求元数据无关，缓存不受影响。
     */
    val wordTimings: List<WordTiming> = emptyList()
) {
    /** 是否已有可直接使用的音频数据。 */
    val hasAudio: Boolean get() = audioBytes != null || audioUrl != null
}

/**
 * TTS 服务商抽象。每个服务商实现自己的请求格式与响应解析，
 * 上层（UI/播放逻辑）只面对这个接口，切换服务商不改业务代码。
 */
interface TtsProvider {
    val id: String
    suspend fun synthesize(config: SettingsRepository.ProviderConfig, text: String): TtsResult
}

/**
 * 各 Provider 共用的 OkHttpClient（进程内单例）。
 * 单例的意义在连接池：整篇文本要按段连续请求同一域名，复用连接能省掉
 * 每段一次的 TLS 握手；每次新建实例则各段各开各的池，白白多花几秒。
 */
internal fun ttsHttpClient(): OkHttpClient = lazySharedClient

private val lazySharedClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
