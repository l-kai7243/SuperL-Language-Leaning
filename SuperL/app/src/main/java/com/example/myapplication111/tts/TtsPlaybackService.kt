package com.example.myapplication111.tts

import android.content.Context
import com.example.myapplication111.data.AudioCache
import com.example.myapplication111.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS 播放服务：把"分段合成 + 缓存 + 合并 + 连续播放"的逻辑从 UI 层抽出来，
 * 主页和收藏详情页共用。服务商无关 —— 内部通过 TtsProviderFactory 走当前服务商。
 *
 * ## 为什么要合并成整篇再播
 *
 * 服务商接口对单次请求的文本长度有硬限制（智谱 GLM-TTS 是 1024 字符），
 * 超长文本只能分段请求。旧实现是"合成一段播一段"，段与段之间由两个不同的
 * MediaPlayer 实例接力，除了可听见的间隙，更难受的是每段都由模型独立起句，
 * 语气基线会重置，听起来像换了个人。
 *
 * 现在的做法：
 * 1. 按服务商的真实字符上限分段（而不是写死 500），尽量按段落切，让每段的
 *    边界落在语义自然的停顿处；
 * 2. 先把所有段合成好并落地缓存，再合并成一个整篇音频文件；
 * 3. 用**单个** MediaPlayer 播放这个整篇文件 —— 段间没有任何播放器切换，
 *    衔接处的间隙彻底消失，同时语速可以整篇统一调节。
 *
 * 整篇合并结果也会缓存，第二次播放同一文本直接命中，不再联网、不再计费。
 */
object TtsPlaybackService {

    /** 准备整篇音频的结果。 */
    sealed class PrepareResult {
        /**
         * 可以播放了。
         *
         * @param audioFile 整篇的本地音频文件，可直接交给 MediaPlayer，也可另存到收藏。
         * @param segmentCount 实际分段数（1 表示一次请求就够）。
         * @param fromWholeCache 是否命中整篇缓存（未产生 API 请求、未做合并）。
         * @param allSegmentsCached 是否所有分段都命中缓存（未产生 API 请求）。
         */
        data class Success(
            val audioFile: File,
            val segmentCount: Int,
            val fromWholeCache: Boolean,
            val allSegmentsCached: Boolean,
            /**
             * 分段时间轴：每个合成段在整篇音频里的起点与时长。
             * 空列表表示拿不到（老缓存没有 sidecar），调用方退回按字数估算。
             */
            val timeline: List<SegmentTiming> = emptyList()
        ) : PrepareResult()

        data class Failure(val message: String) : PrepareResult()

        data object Cancelled : PrepareResult()
    }

    // ------------------------------------------------------------------
    // 文本分段
    // ------------------------------------------------------------------

    /**
     * 把长文本切成不超过 maxChars 的若干段，尽量切在语义自然的位置。
     *
     * 优先级：空行（段落）> 换行 > 句末标点 > 分句标点 > 空格 > 硬切。
     * 高优先级的分隔点只有在"窗口填满至少一半"时才会被采用，
     * 否则退到下一级去找更靠后的位置，避免切出一堆很短的碎片。
     */
    fun splitTextIntoSegments(text: String, maxChars: Int = SettingsRepository.DEFAULT_SEGMENT_CHARS): List<String> {
        val limit = maxChars.coerceAtLeast(1)
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= limit) return listOf(normalized)

        // 从高到低的分隔优先级；每个元素是一组候选分隔符
        val tiers = listOf(
            listOf("\n\n"),
            listOf("\n"),
            listOf(". ", "。", "? ", "？", "! ", "！", "…", "; ", "；"),
            listOf(", ", "，", "、", ": ", "："),
            listOf(" ")
        )

        val segments = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            val remaining = normalized.length - start
            if (remaining <= limit) {
                normalized.substring(start).trim().takeIf { it.isNotBlank() }?.let { segments.add(it) }
                break
            }

            val windowEnd = start + limit
            val minPos = start + limit / 2
            var splitPos = -1

            // 逐级降低分隔优先级；某一级在窗口后半段找到了分隔点就采用。
            // 搜索起点是 windowEnd - sep.length，保证 idx + sep.length <= windowEnd，
            // 否则以 ". " 结尾的段会超出 maxChars 一两个字符 —— 服务商是按字符数
            // 硬卡上限的，超一个字符整段请求都会被拒。
            for (tier in tiers) {
                var best = -1
                for (sep in tier) {
                    val from = (windowEnd - sep.length).coerceAtLeast(start)
                    val idx = normalized.lastIndexOf(sep, from)
                    if (idx >= minPos && idx >= start) {
                        val end = idx + sep.length
                        if (end > best) best = end
                    }
                }
                if (best > start) {
                    splitPos = best
                    break
                }
            }

            if (splitPos <= start) {
                // 没有任何可用的分隔点（比如一长串没有空格的 CJK 文本）：硬切，
                // 但要退到窗口内最后一个空白字符，避免把英文单词切成两半。
                val lastSpace = normalized.lastIndexOf(' ', windowEnd - 1)
                splitPos = if (lastSpace > minPos) lastSpace + 1 else windowEnd
            }

            if (splitPos <= start) splitPos = windowEnd.coerceAtMost(normalized.length)

            normalized.substring(start, splitPos).trim().takeIf { it.isNotBlank() }?.let { segments.add(it) }
            start = splitPos
        }

        return segments
    }

    // ------------------------------------------------------------------
    // 缓存定位
    // ------------------------------------------------------------------

    /** 某段文本是否已有本地缓存文件。 */
    fun cachedSegmentFile(
        context: Context,
        config: SettingsRepository.ProviderConfig,
        segment: String
    ): File = AudioCache.cachedAudioFile(context, segmentCacheKey(config, segment))

    private fun segmentCacheKey(config: SettingsRepository.ProviderConfig, segment: String): String =
        AudioCache.cacheKeyFor(
            segment, config.providerId, config.model, config.voice, config.language, config.speed
        )

    /**
     * 整篇合并音频的缓存文件路径候选（按容器格式各一个）。
     *
     * 之所以是"候选列表"：查缓存时还不知道文件是什么格式（可能是 wav、也可能
     * 是 mp3），只能把几种常见扩展名都试一遍；写入时则用 [detectAudioExtension]
     * 探测出真实格式，选对应的那一个。
     */
    private fun wholeAudioCandidates(
        context: Context,
        config: SettingsRepository.ProviderConfig,
        text: String
    ): List<File> {
        val key = AudioCache.cacheKeyFor(
            text, config.providerId, config.model, config.voice, config.language, config.speed
        )
        val dir = AudioCache.ttsCacheDir(context)
        return AUDIO_EXTENSIONS.map { File(dir, "$key.full.$it") }
    }

    /** 已存在的整篇缓存；没有则返回 null。 */
    fun findWholeAudioFile(
        context: Context,
        config: SettingsRepository.ProviderConfig,
        text: String
    ): File? = wholeAudioCandidates(context, config, text)
        .firstOrNull { it.exists() && it.length() > 0 }

    /** 整篇缓存音频旁的时间轴 sidecar：同名文件、扩展名换成 .json。 */
    fun wholeSidecarFile(wholeAudioFile: File): File =
        File(wholeAudioFile.parentFile, wholeAudioFile.nameWithoutExtension + SIDECAR_EXTENSION)

    /**
     * 已有整篇缓存时顺带取时间轴；老缓存没有 sidecar 时返回 null，
     * 调用方退回按字数估算。（不按当前 maxChars 重新切段来"补算"：
     * 若分段上限在缓存生成后改过，补算出的段边界与真实音频对不上。）
     */
    fun findWholeAudioTimeline(
        context: Context,
        config: SettingsRepository.ProviderConfig,
        text: String
    ): List<SegmentTiming>? =
        findWholeAudioFile(context, config, text)?.let { SegmentTimeline.read(wholeSidecarFile(it)) }

    /** 指定扩展名的整篇缓存路径（写入时用）。 */
    private fun wholeAudioPath(
        context: Context,
        config: SettingsRepository.ProviderConfig,
        text: String,
        extension: String
    ): File {
        val key = AudioCache.cacheKeyFor(
            text, config.providerId, config.model, config.voice, config.language, config.speed
        )
        return File(AudioCache.ttsCacheDir(context), "$key.full.$extension")
    }

    // ------------------------------------------------------------------
    // 合成与合并
    // ------------------------------------------------------------------

    /**
     * 把合成结果落地为本地文件：优先直接写音频字节（响应即音频的服务商，无需二次下载），
     * 否则按 URL 下载。成功返回本地路径；只有 URL 可用时返回该 URL；失败返回 null。
     */
    suspend fun materializeResult(
        context: Context,
        result: TtsResult,
        targetFile: File
    ): String? {
        if (result.audioBytes != null) {
            return try {
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { it.write(result.audioBytes) }
                targetFile.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        if (result.audioUrl != null) {
            val downloaded = AudioCache.downloadToFile(ttsHttpClient(), result.audioUrl, targetFile)
            return if (downloaded) targetFile.absolutePath else result.audioUrl
        }
        return null
    }

    /**
     * 准备好整篇音频：分段 → 逐段合成（带缓存）→ 合并成单一文件。
     *
     * 全程在 IO 线程；调用方通过 [onProgress] 更新进度、通过 [isCancelled] 中途取消。
     * [onProgress] 的回调切回主线程，调用方可以直接写 Compose 状态。
     * 返回的文件可直接交给一个 MediaPlayer 连续播放。
     *
     * 注意：最大字符数由调用方用 SettingsRepository.effectiveMaxChars(context) 取，
     * 它已经考虑了服务商的硬限制和用户的配置。
     */
    suspend fun prepareAudio(
        context: Context,
        text: String,
        config: SettingsRepository.ProviderConfig,
        maxChars: Int,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): PrepareResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext PrepareResult.Failure("文本为空")

        val segments = splitTextIntoSegments(text, maxChars)
        if (segments.isEmpty()) return@withContext PrepareResult.Failure("文本为空")

        suspend fun report(done: Int, total: Int) {
            withContext(Dispatchers.Main) { onProgress(done, total) }
        }

        // 1) 整篇缓存直接命中：不联网、不计费
        findWholeAudioFile(context, config, text)?.let {
            report(segments.size, segments.size)
            return@withContext PrepareResult.Success(
                it,
                segments.size,
                fromWholeCache = true,
                allSegmentsCached = true,
                timeline = SegmentTimeline.read(wholeSidecarFile(it)) ?: emptyList()
            )
        }

        // 缺 Key 且该服务商确实需要 Key 时，先看分段缓存是否已经齐了（齐了就能离线拼出来，不用报错）
        if (SettingsRepository.providerMeta(config.providerId).requiresApiKey &&
            config.apiKey.isBlank()
        ) {
            val cached = collectCachedSegments(context, config, segments)
            if (cached.size != segments.size) {
                return@withContext PrepareResult.Failure("请先在设置中填写当前服务商的 API Key")
            }
        }

        // 2) 逐段合成（命中分段缓存则跳过联网）
        val provider = TtsProviderFactory.create(config.providerId)
        val segmentFiles = ArrayList<File>(segments.size)
        // 每段精确的句级时间（服务商给了词级时间戳才有；分段缓存里没存，命中缓存的段为空）
        val segmentSentences = ArrayList<List<TimingSentence>>(segments.size)
        var usedNetwork = false

        for ((index, segment) in segments.withIndex()) {
            if (isCancelled()) return@withContext PrepareResult.Cancelled
            report(index, segments.size)

            val cacheFile = cachedSegmentFile(context, config, segment)
            if (cacheFile.exists() && cacheFile.length() > 0) {
                segmentFiles.add(cacheFile)
                segmentSentences.add(emptyList())
                continue
            }

            val result = try {
                usedNetwork = true
                provider.synthesize(config, segment)
            } catch (e: Exception) {
                return@withContext PrepareResult.Failure(
                    if (segments.size == 1) "合成失败：${e.message ?: "网络异常"}"
                    else "第 ${index + 1}/${segments.size} 段合成失败：${e.message ?: "网络异常"}"
                )
            }

            if (!result.hasAudio) {
                return@withContext PrepareResult.Failure(
                    if (segments.size == 1) "合成失败：${result.errorMessage ?: "服务商未返回音频"}"
                    else "第 ${index + 1}/${segments.size} 段合成失败：${result.errorMessage ?: "服务商未返回音频"}"
                )
            }

            if (isCancelled()) return@withContext PrepareResult.Cancelled

            val written = materializeResult(context, result, cacheFile)
            val writtenFile = written?.let { File(it) }
            if (writtenFile == null || !writtenFile.exists() || writtenFile.length() == 0L) {
                // materializeResult 在"只有远程 URL 且下载失败"时会返回 URL 本身，
                // 那个 URL 没落地成本地文件，既不能合并也不能离线播放，只能报错。
                val detail = if (written != null) "音频下载失败" else "服务商未返回可用音频"
                return@withContext PrepareResult.Failure(
                    if (segments.size == 1) detail
                    else "第 ${index + 1}/${segments.size} 段$detail"
                )
            }
            segmentFiles.add(writtenFile)
            segmentSentences.add(
                if (result.wordTimings.isEmpty()) emptyList()
                else SegmentTimeline.sentencesFromWords(segment, result.wordTimings)
            )
        }

        if (isCancelled()) return@withContext PrepareResult.Cancelled
        report(segments.size, segments.size)

        // 3) 合并成整篇文件
        val ext = detectAudioExtension(segmentFiles.first())
        val dest = wholeAudioPath(context, config, text, ext)

        try {
            if (segmentFiles.size == 1) {
                segmentFiles.first().copyTo(dest, overwrite = true)
            } else {
                mergeAudioFiles(segmentFiles, dest)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext PrepareResult.Failure("合并音频失败：${e.message ?: "未知错误"}")
        }

        if (!dest.exists() || dest.length() == 0L) {
            return@withContext PrepareResult.Failure("合并音频失败：输出文件为空")
        }

        // 4) 记录分段时间轴（播放页逐句高光、进度条定位的依据），与整篇文件放在一起。
        //    每段的时长此刻精确可测；段内各句优先用服务商的词级时间戳（精确），
        //    没有的段退回按字数权重估算。
        val timings = buildSegmentTimings(segments, segmentFiles, segmentSentences)
        if (timings.isNotEmpty()) {
            SegmentTimeline.write(wholeSidecarFile(dest), timings)
        }

        PrepareResult.Success(
            audioFile = dest,
            segmentCount = segments.size,
            fromWholeCache = false,
            allSegmentsCached = !usedNetwork,
            timeline = timings
        )
    }

    /** 收集全部已缓存的分段文件；有缺失就返回不完整的列表（长度对不上即为缺失）。 */
    private fun collectCachedSegments(
        context: Context,
        config: SettingsRepository.ProviderConfig,
        segments: List<String>
    ): List<File> = segments.mapNotNull { segment ->
        cachedSegmentFile(context, config, segment).takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * 只用本地缓存拼出整篇音频，**绝不联网**。
     *
     * 收藏页和"保存到收藏"用它：用户已经完整听过一遍的文本，音频都在缓存里，
     * 这里把分段合并成一个文件存下来即可，不会再次产生 API 费用。
     * 缓存不齐（还有段没听过）时返回 null，由调用方决定是提示还是去合成。
     */
    suspend fun audioFromCacheOnly(
        context: Context,
        text: String,
        config: SettingsRepository.ProviderConfig,
        maxChars: Int
    ): File? = withContext(Dispatchers.IO) {
        findWholeAudioFile(context, config, text)?.let { return@withContext it }

        val segments = splitTextIntoSegments(text, maxChars)
        if (segments.isEmpty()) return@withContext null
        val files = collectCachedSegments(context, config, segments)
        if (files.size != segments.size) return@withContext null

        val dest = wholeAudioPath(context, config, text, detectAudioExtension(files.first()))
        try {
            if (files.size == 1) {
                files.first().copyTo(dest, overwrite = true)
            } else {
                mergeAudioFiles(files, dest)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
        if (dest.exists() && dest.length() > 0) {
            // 与 prepareAudio 一样落时间轴，收藏播放页才能逐句高亮
            val timings = buildSegmentTimings(
                segments, files,
                List(segments.size) { emptyList<TimingSentence>() }
            )
            if (timings.isNotEmpty()) {
                SegmentTimeline.write(wholeSidecarFile(dest), timings)
            }
            dest
        } else {
            null
        }
    }

    // ------------------------------------------------------------------
    // 分段时间轴辅助：测时长
    // ------------------------------------------------------------------

    /**
     * 按顺序测量每个分段文件的时长，累出各段在整篇音频里的起点，并挂上段内
     * 精确句级时间（如有）。有段测不出时长就整体放弃。
     */
    private fun buildSegmentTimings(
        segmentTexts: List<String>,
        segmentFiles: List<File>,
        segmentSentences: List<List<TimingSentence>>
    ): List<SegmentTiming> {
        if (segmentTexts.size != segmentFiles.size) return emptyList()
        val timings = ArrayList<SegmentTiming>(segmentFiles.size)
        var start = 0L
        for (i in segmentFiles.indices) {
            val duration = audioDurationMs(segmentFiles[i])
            if (duration <= 0L) return emptyList()
            timings.add(
                SegmentTiming(
                    segmentTexts[i], start, duration,
                    segmentSentences.getOrElse(i) { emptyList() }
                )
            )
            start += duration
        }
        return timings
    }

    /**
     * 本地音频文件的时长（毫秒）；测不出来返回 0。
     * WAV 直接用头部数据算（快、无解码器开销），其余格式交给系统解码器。
     */
    fun audioDurationMs(file: File): Long {
        if (isWavFile(file)) {
            parseWavHeader(file)?.let { layout ->
                if (layout.byteRate > 0) {
                    return layout.dataLength * 1000L / layout.byteRate
                }
            }
        }
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 合并多个音频文件为一个。
     *
     * WAV：正确解析 RIFF 块结构，只拼接各文件的 data 块内容，并重写头部里的
     * RIFF 总长度与 data 长度（旧实现硬编码跳过 44 字节，一旦文件带 LIST/INFO
     * 之类的额外块就会把后续音频数据当成头丢掉，出现杂音或截断）。
     * MP3 / OGG / AAC：帧结构独立，顺序拼接即可；MP3 的后续文件如果有 ID3v2 标签
     * 会先跳过，否则标签会变成一段可听见的噪声。
     */
    fun mergeAudioFiles(sourceFiles: List<File>, destFile: File) {
        if (sourceFiles.isEmpty()) return
        destFile.parentFile?.mkdirs()

        if (isWavFile(sourceFiles.first())) {
            mergeWavFiles(sourceFiles, destFile)
        } else {
            mergeRawFiles(sourceFiles, destFile)
        }
    }

    /** WAV 合并：解析头部 → 重写长度字段 → 顺序追加各文件的 data 内容。 */
    private fun mergeWavFiles(sourceFiles: List<File>, destFile: File) {
        val headers = sourceFiles.map { parseWavHeader(it) }
        val layouts = headers.filterNotNull()
        if (layouts.size != sourceFiles.size) {
            // 有一个解析不了就退回纯拼接，至少不会崩
            mergeRawFiles(sourceFiles, destFile)
            return
        }

        val totalData = layouts.sumOf { it.dataLength.toLong() }
        if (totalData <= 0L || totalData > Int.MAX_VALUE - 1024) {
            mergeRawFiles(sourceFiles, destFile)
            return
        }

        val first = layouts.first()
        destFile.outputStream().use { out ->
            // 头部：原样复制到 data 块内容开始之前（含 data 块 id + size 字段）
            val headerBytes = first.rawBytes.copyOfRange(0, first.dataStart)
            patchInt32(headerBytes, 4, (headerBytes.size - 8 + totalData).toInt())  // RIFF chunk size
            patchInt32(headerBytes, first.dataSizeFieldOffset, totalData.toInt())   // data chunk size
            out.write(headerBytes)

            sourceFiles.forEachIndexed { index, file ->
                file.inputStream().use { input ->
                    val layout = layouts[index]
                    if (!input.skipExact(layout.dataStart.toLong())) return@use
                    input.copyRangeTo(out, layout.dataLength.toLong())
                }
            }
        }
    }

    /**
     * 非 WAV（MP3/OGG/AAC…）：顺序拼接；后续文件的 MP3 ID3v2 标签会被跳过，
     * 否则标签里的二进制元数据会作为一段可听噪声混进音频。
     */
    private fun mergeRawFiles(sourceFiles: List<File>, destFile: File) {
        destFile.outputStream().use { out ->
            sourceFiles.forEachIndexed { index, file ->
                file.inputStream().use { raw ->
                    if (index > 0) {
                        // BufferedInputStream 支持 mark/reset，能"看一眼再决定"
                        val input = java.io.BufferedInputStream(raw)
                        skipId3v2IfPresent(input)
                        input.copyTo(out)
                    } else {
                        raw.copyTo(out)
                    }
                }
            }
        }
    }

    /**
     * 流开头若是 ID3v2 标签则跳过去（10 字节头 + 声明长度 + 可选 10 字节 footer），
     * 否则把读掉的字节 reset 回去。要求 input 支持 mark/reset。
     */
    private fun skipId3v2IfPresent(input: java.io.BufferedInputStream) {
        input.mark(16)
        val head = ByteArray(10)
        var read = 0
        while (read < 10) {
            val n = input.read(head, read, 10 - read)
            if (n < 0) break
            read += n
        }
        val isId3 = read >= 10 &&
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        if (isId3) {
            // ID3v2 长度是 syncsafe integer：每字节只用低 7 位
            val size = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
            val hasFooter = (head[5].toInt() and 0x10) != 0
            input.skipExact(size.toLong() + if (hasFooter) 10L else 0L)
        } else {
            input.reset()
        }
    }

    // ------------------------------------------------------------------
    // WAV / MP3 头部解析辅助
    // ------------------------------------------------------------------

    /** WAV 文件里 data 块的位置与长度，外加原始头部字节（用于改长度后重新写出）。 */
    private class WavLayout(
        val rawBytes: ByteArray,
        val dataStart: Int,
        val dataLength: Int,
        val dataSizeFieldOffset: Int,
        /** fmt 块里的 byteRate（每秒字节数），用来反推音频时长；解析不到为 0。 */
        val byteRate: Int
    )

    /**
     * 解析 RIFF/WAVE 的块结构，定位 data 块。
     * 只读文件开头最多 [MAX_WAV_HEADER_BYTES] 字节（不整文件读入内存——一段一小时的
     * 24kHz 单声道 WAV 有上百 MB）。返回 null 表示这不是一个结构完整的 WAV。
     */
    private fun parseWavHeader(file: File): WavLayout? {
        return try {
            val head = ByteArray(MAX_WAV_HEADER_BYTES)
            val headLen = file.inputStream().use { stream ->
                var total = 0
                while (total < head.size) {
                    val n = stream.read(head, total, head.size - total)
                    if (n < 0) break
                    total += n
                }
                total
            }
            if (headLen < 44 || !isWavHeader(head)) return null

            // RIFF 头里声明的总长度，用来校正个别编码器把 data size 写错的情况
            val riffSize = readInt32LE(head, 4)
            val fileLength = file.length()

            var pos = 12
            var fmtByteRate = 0
            while (pos + 8 <= headLen) {
                val id = String(head, pos, 4, Charsets.US_ASCII)
                val size = readInt32LE(head, pos + 4)
                if (size < 0) return null
                if (id == "fmt " && size >= 16) {
                    // PCM fmt 块数据区：偏移 4 是 sampleRate，偏移 8 是 byteRate
                    fmtByteRate = readInt32LE(head, pos + 16)
                }
                if (id == "data") {
                    val declared = size
                    // 按文件真实长度纠偏：RIFF 里声明的总长 + 8 就是文件应有的长度
                    val expectedData = if (riffSize in 0..Int.MAX_VALUE) {
                        (riffSize.toLong() + 8L) - (pos + 8)
                    } else {
                        -1L
                    }
                    val fromFileLength = fileLength - (pos + 8)
                    val actual = when {
                        expectedData in 1..fromFileLength -> expectedData
                        else -> fromFileLength
                    }
                    if (actual > Int.MAX_VALUE) return null
                    val usable = if (declared in 1..actual.toInt()) declared.toLong() else actual
                    if (usable <= 0L) return null
                    return WavLayout(head.copyOf(pos + 8), pos + 8, usable.toInt(), pos + 4, fmtByteRate)
                }
                // 块长度为奇数时有一个填充字节
                pos += 8 + size + (size and 1)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun patchInt32(bytes: ByteArray, offset: Int, value: Int) {
        if (offset + 4 > bytes.size) return
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** 通过 RIFF/WAVE 魔数判断是否为 WAV 文件。 */
    fun isWavFile(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(12)
            val read = stream.read(header)
            read >= 12 && isWavHeader(header)
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 常见音频容器魔数（WAV / MP3 / OGG / FLAC / M4A）。
     * 部分厂商的 Content-Type 不可靠，按字节头兜底最稳。
     */
    fun looksLikeAudioBytes(header: ByteArray): Boolean {
        if (header.size < 4) return false
        return isWavHeader(header) ||
            // ID3 标签或 MP3 帧同步字
            (header.size >= 3 && header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
            (header.size >= 2 && (header[0].toInt() and 0xFF) == 0xFF &&
                (header[1].toInt() and 0xE0) == 0xE0) ||
            (header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()) ||
            (header.size >= 4 && header[0] == 'f'.code.toByte() &&
                header[1] == 'L'.code.toByte() && header[2] == 'a'.code.toByte() &&
                header[3] == 'C'.code.toByte()) ||
            (header.size >= 8 && header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte())
    }

    private fun isWavHeader(header: ByteArray): Boolean =
        header.size >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
            header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()

    /**
     * 按文件头魔数探测音频容器格式，返回文件名该用的扩展名。
     * 比信任服务商返回的 response_format 可靠 —— 厂商经常报 wav 却回 mp3。
     */
    fun detectAudioExtension(file: File): String = try {
        file.inputStream().use { stream ->
            val header = ByteArray(12)
            val read = stream.read(header)
            when {
                isWavHeader(header) ->
                    "wav"
                read >= 4 &&
                    header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() ->
                    "ogg"
                read >= 4 &&
                    header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() ->
                    "flac"
                read >= 8 &&
                    header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() ->
                    "m4a"
                // ID3 标签或 MP3 帧同步字
                read >= 3 &&
                    header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte() -> "mp3"
                read >= 2 &&
                    (header[0].toInt() and 0xFF) == 0xFF &&
                    (header[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                else -> "wav"
            }
        }
    } catch (e: Exception) {
        "wav"
    }

    /**
     * 解析 WAV 头时最多读取的字节数。真实头部通常只有几十字节，
     * 留足空间容纳厂商可能插入的 LIST/INFO 等元数据块，同时避免整文件读入内存。
     */
    private const val MAX_WAV_HEADER_BYTES = 64 * 1024

    /** 查整篇缓存时要试的扩展名，顺序即优先级。 */
    private val AUDIO_EXTENSIONS = listOf("wav", "mp3", "ogg", "flac", "m4a")

    /** 整篇音频的时间轴 sidecar 扩展名（与音频同名，仅扩展名不同）。 */
    private const val SIDECAR_EXTENSION = ".json"
}

/** 跳过恰好 n 个字节；返回是否跳够。 */
private fun java.io.InputStream.skipExact(n: Long): Boolean {
    var remaining = n
    while (remaining > 0) {
        val skipped = try {
            skip(remaining)
        } catch (e: Exception) {
            return false
        }
        if (skipped <= 0) {
            // skip 可能返回 0，退化为逐字节读
            if (read() < 0) return false
            remaining--
        } else {
            remaining -= skipped
        }
    }
    return true
}

/** 从当前流位置复制恰好 n 个字节到输出流。 */
private fun java.io.InputStream.copyRangeTo(out: java.io.OutputStream, n: Long) {
    val buffer = ByteArray(64 * 1024)
    var remaining = n
    while (remaining > 0) {
        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
        val read = read(buffer, 0, toRead)
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
}
