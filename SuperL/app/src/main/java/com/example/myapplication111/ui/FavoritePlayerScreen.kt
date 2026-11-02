package com.example.myapplication111.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication111.data.AudioCache
import com.example.myapplication111.data.FavoritesAudioStore
import com.example.myapplication111.data.FavoritesRepository
import com.example.myapplication111.data.SettingsRepository
import com.example.myapplication111.tts.SegmentTimeline
import com.example.myapplication111.tts.SegmentTiming
import com.example.myapplication111.tts.TtsPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 收藏播放页（只读）。像音乐软件一样：
 * - 正文以句子为单位逐行展示，正在读的那一句高亮并自动滚动到可视区；
 * - 可拖动的进度条，拖完跳到对应位置继续读；
 * - 大按钮播放/暂停；点任意一句可以直接跳到那句的开头。
 *
 * 修改文本在独立的编辑页（[FavoriteEditScreen]），本页面不做任何编辑。
 *
 * ## 逐句高光的时间从哪来
 *
 * 整篇音频是"逐段合成再拼接"的，段的边界时间在合并时精确可测，随音频存在
 * sidecar 里（[SegmentTimeline]）。有 sidecar 时段内各句按字数权重估算、段边界
 * 精确；没有 sidecar（老缓存）时整篇按字数权重估算。两种情况误差都不跨段累积。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritePlayerScreen(
    favoriteId: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var item by remember {
        mutableStateOf(favoriteId?.let { id -> FavoritesRepository.load(context).firstOrNull { it.id == id } })
    }

    var isPlaying by remember { mutableStateOf(false) }
    var hasTrack by remember { mutableStateOf(false) }
    val synthesis = rememberSynthesisState()

    var speed by remember { mutableStateOf(SettingsRepository.getSpeechSpeed(context)) }

    // 播放进度与总时长（毫秒）
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    // 拖动进度条期间的位置（松手前 UI 只认它，不被轮询覆盖）
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableIntStateOf(0) }

    // 当前音频的分段时间轴；null 表示没有 sidecar，按字数估算
    var timeline by remember { mutableStateOf<List<SegmentTiming>?>(null) }

    val player = rememberTtsAudioPlayer(
        initialSpeed = speed,
        onPlayingChanged = { isPlaying = it },
        onPrepared = { d ->
            hasTrack = true
            if (d > 0) durationMs = d
        },
        onCompleted = {
            hasTrack = false
            positionMs = 0
            durationMs = 0
            isDragging = false
            Toast.makeText(context, "播放完成", Toast.LENGTH_SHORT).show()
        },
        onError = { msg ->
            hasTrack = false
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    )

    LaunchedEffect(speed) {
        player.speed = speed
    }

    // 进度轮询：每 100ms 刷一次。暂停时也轮询 —— currentPosition 在暂停态保持原值
    LaunchedEffect(Unit) {
        while (true) {
            if (!isDragging) {
                positionMs = player.currentPositionMs
                if (durationMs <= 0) {
                    val d = player.durationMs
                    if (d > 0) durationMs = d
                }
            }
            delay(100)
        }
    }

    // ---- 歌词行与当前行 ----

    // 时间轴的段时长加总应与播放器报的整篇时长一致；差太多说明 sidecar 与
    // 实际音频不匹配（异常情况），退回按字数估算
    val usableTimeline = timeline?.takeIf { segments ->
        durationMs <= 0 ||
            abs(segments.sumOf { it.durationMs } - durationMs) <= durationMs * 0.15
    }

    val lyricLines = remember(usableTimeline, durationMs, item?.text) {
        val current = item
        when {
            current == null -> emptyList()
            // 时间轴自带每段的精确文本（与音频实际内容一致），展示以它为准
            usableTimeline != null -> SegmentTimeline.buildLyricLines(usableTimeline)
            else -> SegmentTimeline.buildEstimatedLines(current.text, durationMs.toLong())
        }
    }

    val displayedPositionMs = if (isDragging) dragPositionMs else positionMs
    val canHighlight = usableTimeline != null || durationMs > 0
    val currentLine = if (canHighlight) {
        SegmentTimeline.currentLineIndex(lyricLines, displayedPositionMs.toLong())
    } else {
        -1
    }

    val listState = rememberLazyListState()
    // 当前行变化时把它滚到屏幕上三分之一处（音乐软件歌词的跟随方式）
    LaunchedEffect(currentLine) {
        if (currentLine < 0) return@LaunchedEffect
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentLine }
        if (info == null) {
            listState.scrollToItem(currentLine)
        } else {
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport > 0) {
                listState.animateScrollBy(info.offset - viewport * 0.3f)
            }
        }
    }

    // ---- 操作 ----

    fun seekTo(targetMs: Int) {
        player.seekTo(targetMs)
        positionMs = targetMs.coerceAtLeast(0)
    }

    fun beginPlayback() {
        val current = item ?: return
        scope.launch {
            // 1) 收藏自带的音频：直接播，不联网不花钱
            val stored = withContext(Dispatchers.IO) {
                FavoritesAudioStore.fileFor(context, current.id, current.audioFileName)
            }
            if (stored != null) {
                timeline = withContext(Dispatchers.IO) {
                    SegmentTimeline.read(FavoritesAudioStore.timelineFileFor(context, current.id))
                }
                player.play(stored.absolutePath, speed)
                return@launch
            }

            val config = SettingsRepository.getSynthesisConfig(context)

            // 2) 整篇缓存：也不联网
            val wholeCached = TtsPlaybackService.findWholeAudioFile(context, config, current.text)
            if (wholeCached != null) {
                timeline = withContext(Dispatchers.IO) {
                    TtsPlaybackService.findWholeAudioTimeline(context, config, current.text)
                }
                player.play(wholeCached.absolutePath, speed)
                return@launch
            }

            if (SettingsRepository.providerMeta(config.providerId).requiresApiKey &&
                config.apiKey.isBlank()
            ) {
                Toast.makeText(context, "请先在设置中填写当前服务商的 API Key", Toast.LENGTH_LONG).show()
                return@launch
            }

            // 3) 都没有：整篇合成。进度、取消、失败提示都在 SynthesisState 里
            synthesis.prepare(context, scope, current.text) { result ->
                // 首次听完这次，把整篇音频另存一份到收藏目录，
                // 下次打开无需联网、也无需重新合并。
                val savedName = withContext(Dispatchers.IO) {
                    saveToFavorites(context, current.id, result.audioFile, result.timeline)
                }
                if (savedName != null && savedName != current.audioFileName) {
                    val updated = current.copy(audioFileName = savedName)
                    FavoritesRepository.update(context, updated)
                    item = updated
                }

                timeline = result.timeline.ifEmpty {
                    withContext(Dispatchers.IO) {
                        SegmentTimeline.read(FavoritesAudioStore.timelineFileFor(context, current.id))
                    }
                }
                player.play(result.audioFile.absolutePath, speed)
            }
        }
    }

    // ---- UI ----

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "收藏详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                }
            )
        }
    ) { innerPadding ->
        val current = item
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("收藏不存在")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 正文：歌词式逐句展示
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                itemsIndexed(lyricLines) { index, line ->
                    val isCurrent = index == currentLine
                    Text(
                        text = line.text,
                        style = if (isCurrent) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = hasTrack && durationMs > 0) {
                                seekTo(line.startMs.toInt())
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    )
                }
            }

            // 合成进度
            if (synthesis.isPreparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (synthesis.preparedTotal > 0) {
                            "正在合成整篇音频 ${synthesis.preparedDone}/${synthesis.preparedTotal} 段…"
                        } else "正在准备音频…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { synthesis.cancel(context) }) {
                        Text("取消")
                    }
                }
            }

            // 可拖动的进度条
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = displayedPositionMs.toFloat()
                        .coerceIn(0f, maxOf(1f, durationMs.toFloat())),
                    onValueChange = {
                        isDragging = true
                        dragPositionMs = it.toInt()
                    },
                    onValueChangeFinished = {
                        seekTo(dragPositionMs)
                        isDragging = false
                    },
                    valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f),
                    enabled = hasTrack && durationMs > 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatPlaybackTime(displayedPositionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (durationMs > 0) formatPlaybackTime(durationMs) else "--:--",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 播放/暂停 + 回到开头
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { seekTo(0) },
                    enabled = hasTrack
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = "回到开头")
                }
                Spacer(modifier = Modifier.width(24.dp))
                FilledIconButton(
                    onClick = {
                        when {
                            isPlaying -> player.pause()
                            hasTrack -> player.resume()
                            else -> beginPlayback()
                        }
                    },
                    enabled = !synthesis.isPreparing,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            SpeedControl(
                speed = speed,
                onSpeedChange = { speed = it },
                onSpeedChangeFinished = { SettingsRepository.setSpeechSpeed(context, it) },
                enabled = !synthesis.isPreparing
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 把整篇音频复制到收藏目录并写时间轴 sidecar，返回文件名；失败返回 null。
 * 按真实容器格式给扩展名，不再一律写成 .wav。
 */
private fun saveToFavorites(
    context: android.content.Context,
    favoriteId: String,
    source: File,
    timeline: List<SegmentTiming>
): String? {
    return try {
        val destName = FavoritesAudioStore.fileNameFor(favoriteId, source.extension)
        val dest = File(AudioCache.favoritesAudioDir(context), destName)
        // 先清掉该收藏名下的旧文件（含时间轴），避免换格式后新旧并存
        FavoritesAudioStore.deleteFor(context, favoriteId, "")
        source.copyTo(dest, overwrite = true)
        if (timeline.isNotEmpty()) {
            SegmentTimeline.write(FavoritesAudioStore.timelineFileFor(context, favoriteId), timeline)
        }
        if (dest.exists() && dest.length() > 0) destName else null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** 播放进度显示：m:ss（超过一小时按总分钟数展示）。 */
internal fun formatPlaybackTime(ms: Int): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
