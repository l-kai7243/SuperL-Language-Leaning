package com.example.myapplication111.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 音频文件缓存与下载。
 *
 * - tts_cache：TTS 合成结果缓存，缓存键 = SHA-256(文本|服务商|模型|音色|语言)，
 *   命中缓存即可离线播放、不消耗 API 费用。可在设置页一键清除。
 * - favorites_audio：收藏对应的音频，独立于缓存目录，清缓存不会误删。
 */
object AudioCache {

    fun ttsCacheDir(context: Context): File {
        val dir = File(context.filesDir, "tts_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun favoritesAudioDir(context: Context): File {
        val dir = File(context.filesDir, "favorites_audio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 相同文本 + 相同服务商配置命中同一缓存文件；不同服务商互不污染。
     *
     * speed 只在**偏离 1.0** 时才进入缓存键。这样有两个好处：
     * 一是"播放器实时变速"模式下 speed 恒为 1.0，用户随便拖滑杆也不会让同一段
     * 文本反复生成新缓存；二是老版本（键里没有 speed）留下的缓存仍然算命中，
     * 升级后不会因为键变了而把已有音频全部作废、被迫重新付费合成一遍。
     */
    fun cacheKeyFor(
        text: String,
        providerId: String,
        model: String,
        voice: String,
        language: String,
        speed: Float = 1.0f
    ): String {
        // v2: 切换为自然段与完整语句断句后，与旧版按行断句的缓存键隔离，避免命中带句中停顿的旧音频
        val raw = if (speed == 1.0f) {
            "v2|$text|$providerId|$model|$voice|$language"
        } else {
            "v2|$text|$providerId|$model|$voice|$language|$speed"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun cachedAudioFile(context: Context, key: String): File =
        File(ttsCacheDir(context), "$key.wav")

    /** 在 IO 线程下载 url 内容写入 targetFile；失败清理不完整文件。 */
    suspend fun downloadToFile(client: OkHttpClient, url: String, targetFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val bytes = response.body?.bytes() ?: return@withContext false
                    if (bytes.isEmpty()) return@withContext false
                    targetFile.outputStream().use { it.write(bytes) }
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (targetFile.exists()) targetFile.delete()
                false
            }
        }
    }

    fun cacheSizeBytes(context: Context): Long =
        ttsCacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(context: Context) {
        ttsCacheDir(context).listFiles()?.forEach { it.delete() }
    }
}
