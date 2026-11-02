package com.example.myapplication111.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication111.data.SettingsRepository
import com.example.myapplication111.tts.TtsAudioPlayer
import com.example.myapplication111.tts.TtsPlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 页面级播放器：回调接线与释放集中在这里，页面销毁时自动释放并解绑回调。
 * 三个用到播放器的页面（首页、收藏播放页、生词本）原来各抄一份一模一样的
 * DisposableEffect 模板，现在只有这一份。
 */
@Composable
fun rememberTtsAudioPlayer(
    initialSpeed: Float = 1.0f,
    onPlayingChanged: ((Boolean) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    onCompleted: (() -> Unit)? = null,
    onPrepared: ((Int) -> Unit)? = null
): TtsAudioPlayer {
    val context = LocalContext.current
    val player = remember {
        TtsAudioPlayer(context).apply { speed = initialSpeed }
    }
    DisposableEffect(Unit) {
        player.onPlayingChanged = onPlayingChanged
        player.onError = onError
        player.onCompleted = onCompleted
        player.onPrepared = onPrepared
        onDispose {
            player.onPlayingChanged = null
            player.onError = null
            player.onCompleted = null
            player.onPrepared = null
            player.release()
        }
    }
    return player
}

/**
 * "整篇合成"流程的 UI 状态：分段进度、取消句柄、统一的失败提示。
 *
 * 首页与收藏播放页共用 —— 原来两边各抄一份几乎相同的协程模板（进度回调、
 * 用 Job 身份判断取消、CancellationException 的处理），收敛到这里后这些
 * 容易抄错的细节只有一份实现。
 */
class SynthesisState {

    var isPreparing by mutableStateOf(false)
        private set
    var preparedDone by mutableIntStateOf(0)
        private set
    var preparedTotal by mutableIntStateOf(0)
        private set

    private var job: Job? = null

    /** 页面销毁时调用：取消进行中的合成，避免返回后还在后台烧 API 额度。 */
    fun dispose() {
        job?.cancel()
        job = null
    }

    /** 用户主动取消合成。 */
    fun cancel(context: Context) {
        if (!isPreparing) return
        job?.cancel()
        job = null
        isPreparing = false
        preparedDone = 0
        preparedTotal = 0
        Toast.makeText(context, "已取消合成", Toast.LENGTH_SHORT).show()
    }

    /**
     * 开始合成整篇音频。成功时在主线程回调 [onSuccess]，由调用方决定
     * 直接播放还是另存收藏；失败与取消在这里统一处理。
     */
    fun prepare(
        context: Context,
        scope: CoroutineScope,
        text: String,
        onSuccess: suspend (TtsPlaybackService.PrepareResult.Success) -> Unit
    ) {
        if (isPreparing) return
        isPreparing = true
        preparedDone = 0
        preparedTotal = 0

        val config = SettingsRepository.getSynthesisConfig(context)
        val maxChars = SettingsRepository.effectiveMaxChars(context)

        job = scope.launch {
            // 用本次任务自己的 Job 判断是否已取消：比一个共享布尔标记更可靠
            // （取消后立刻重播时不会被新任务误重置）
            val own = coroutineContext[Job]
            try {
                val result = TtsPlaybackService.prepareAudio(
                    context = context,
                    text = text,
                    config = config,
                    maxChars = maxChars,
                    onProgress = { done, total ->
                        preparedDone = done
                        preparedTotal = total
                    },
                    isCancelled = { own?.isActive != true }
                )
                when (result) {
                    is TtsPlaybackService.PrepareResult.Success -> {
                        isPreparing = false
                        onSuccess(result)
                    }
                    is TtsPlaybackService.PrepareResult.Failure -> {
                        isPreparing = false
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                    TtsPlaybackService.PrepareResult.Cancelled -> isPreparing = false
                }
            } catch (e: CancellationException) {
                // 用户主动取消，不需要提示
                isPreparing = false
                preparedDone = 0
                preparedTotal = 0
                throw e
            } catch (e: Exception) {
                isPreparing = false
                Toast.makeText(
                    context,
                    "合成失败：${e.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                // 只清掉自己：若期间用户又发起了新一轮合成，不能误清新任务的句柄
                if (job === own) job = null
            }
        }
    }
}

/** 创建与当前页面生命周期绑定的 [SynthesisState]。 */
@Composable
fun rememberSynthesisState(): SynthesisState {
    val state = remember { SynthesisState() }
    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }
    return state
}
