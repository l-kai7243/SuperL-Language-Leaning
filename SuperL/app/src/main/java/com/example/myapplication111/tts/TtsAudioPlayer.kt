package com.example.myapplication111.tts

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import android.widget.Toast
import com.example.myapplication111.data.SettingsRepository

/**
 * 单个音频文件的播放器封装，带实时语速控制、暂停/续播与 seek。
 *
 * 语速用 MediaPlayer 的 [PlaybackParams] 实现：Android 底层走的是时间伸缩
 * （time-stretch），**音调保持不变** —— 0.5x 不会变成慢速低吼，2.0x 也不会变尖。
 * 好处是拖滑杆立刻生效，不用重新请求 TTS、不产生任何费用。
 *
 * 之所以做成独立的类而不是继续用散在 UI 里的 MediaPlayer：
 * 现在整篇文本合并成**一个**文件播放，播放器实例的生命周期只跟"播放"按钮绑定；
 * 语速变化只需要把新值喂给正在播的实例即可，不必重建。
 * 暂停/续播同样作用在这个实例上 —— 不重建实例才不会丢失播放位置，
 * 拖进度条（seekTo）也才能精确落到想要的句子。
 */
class TtsAudioPlayer(private val context: Context) {

    companion object {
        /**
         * seek 后最多等这么久就不再"以目标位置为准"。正常设备 onSeekComplete
         * 很快回调；这只是个别 ROM 不回调时的兜底，宁可短暂显示旧值也不永久卡住。
         */
        private const val PENDING_SEEK_TIMEOUT_MS = 1500L
    }

    private var player: MediaPlayer? = null

    /** 设备不支持变速时只提示一次，避免每拖一下滑杆弹一个 Toast。 */
    private var warnedUnsupported = false

    /** 是否已经给当前实例设过非 1.0 的语速（用于决定要不要把 1.0 也写回去）。 */
    private var appliedNonDefaultSpeed = false

    /**
     * 进行中的 seek 目标位置。MediaPlayer.seekTo 是异步的：完成之前
     * currentPosition 仍可能报旧位置，而 UI 每 100ms 轮询一次进度 —— 不加
     * 这层记忆，跳转后进度条和歌词高光会被旧位置打回去、继续顺着旧时间往前滚，
     * 看起来就是"字幕没跟着音频跳"。
     */
    private var pendingSeekTargetMs: Int? = null
    private var pendingSeekAtMs: Long = 0L

    /** 当前语速，写进去即刻作用于正在播放的实例。 */
    var speed: Float = 1.0f
        set(value) {
            field = value.coerceIn(SettingsRepository.MIN_SPEED, SettingsRepository.MAX_SPEED)
            player?.let { applySpeed(it, field) }
        }

    /** 播放状态变化回调（UI 用来切换"播放/暂停"按钮）。 */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /**
     * 播放器就绪回调，参数是整篇音频的时长（毫秒；拿不到为 0）。
     * 进度条的总长度、进度轮询都以它为准。
     */
    var onPrepared: ((durationMs: Int) -> Unit)? = null

    /** 自然播放结束回调（不含用户主动停止）。 */
    var onCompleted: (() -> Unit)? = null

    /** 播放出错回调，参数是给用户看的原因。 */
    var onError: ((String) -> Unit)? = null

    val isPlaying: Boolean
        get() = player?.let {
            try {
                it.isPlaying
            } catch (e: Exception) {
                false
            }
        } ?: false

    /** 是否有已就绪/待播放的音频实例（暂停时也为 true，供"续播"判断）。 */
    val hasTrack: Boolean
        get() = player != null

    /** 整篇音频时长（毫秒）；没有就绪的实例时为 0。 */
    val durationMs: Int
        get() = player?.let { runCatching { it.duration }.getOrDefault(0) } ?: 0

    /** 当前播放位置（毫秒）；没有实例时为 0。暂停时保持原位置。 */
    val currentPositionMs: Int
        get() {
            val mp = player
            if (mp == null) {
                val target = pendingSeekTargetMs
                pendingSeekTargetMs = null
                return target ?: 0
            }
            val target = pendingSeekTargetMs ?: return runCatching { mp.currentPosition }.getOrDefault(0)
            // seek 完成前以目标位置为准；个别 ROM 不回调 onSeekComplete，
            // 用超时兜底退出"以目标为准"的状态，不至于一直停在目标值上
            if (SystemClock.elapsedRealtime() - pendingSeekAtMs > PENDING_SEEK_TIMEOUT_MS) {
                pendingSeekTargetMs = null
                return runCatching { mp.currentPosition }.getOrDefault(target)
            }
            return target
        }

    /**
     * 播放一个本地文件路径或远程 URL。会先释放上一个实例。
     * @param startSpeed 这次播放的起始语速；调用方通常传当前设置里的语速。
     */
    fun play(source: String, startSpeed: Float = speed) {
        // 先释放旧实例再改语速：否则 speed 的 setter 会把新速度写到即将被销毁的实例上
        releaseCurrent()
        appliedNonDefaultSpeed = false
        pendingSeekTargetMs = null
        speed = startSpeed

        try {
            val mp = MediaPlayer()
            mp.setDataSource(source)

            mp.setOnPreparedListener { prepared ->
                prepared.start()
                // start 之后再设语速：setPlaybackParams 在不同 ROM 上对状态机的
                // 处理不一致，放在 start 后面可以避免它意外改变播放状态。
                applySpeed(prepared, speed)
                onPlayingChanged?.invoke(true)
                onPrepared?.invoke(runCatching { prepared.duration }.getOrDefault(0))
            }
            mp.setOnCompletionListener {
                releaseCurrent()
                onPlayingChanged?.invoke(false)
                onCompleted?.invoke()
            }
            // seek 完成：真实位置可用了，退出"以目标位置为准"的状态
            mp.setOnSeekCompleteListener {
                pendingSeekTargetMs = null
            }
            mp.setOnErrorListener { _, what, _ ->
                releaseCurrent()
                onPlayingChanged?.invoke(false)
                onError?.invoke("播放失败（错误码 $what）")
                true
            }

            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            releaseCurrent()
            onPlayingChanged?.invoke(false)
            onError?.invoke("播放失败：${e.message ?: "未知错误"}")
        }
    }

    /** 暂停。保留实例与位置，[resume] 后从暂停处继续。 */
    fun pause() {
        val mp = player ?: return
        val paused = runCatching {
            if (mp.isPlaying) mp.pause()
            true
        }.getOrDefault(false)
        if (paused) onPlayingChanged?.invoke(false)
    }

    /** 从暂停处续播。 */
    fun resume() {
        val mp = player ?: return
        val resumed = runCatching {
            if (!mp.isPlaying) mp.start()
            applySpeed(mp, speed)
            true
        }.getOrDefault(false)
        if (resumed) onPlayingChanged?.invoke(true)
    }

    /** 跳转到指定位置（毫秒）。实例未就绪或时长未知时是空操作。 */
    fun seekTo(positionMs: Int) {
        val mp = player ?: return
        runCatching {
            val duration = mp.duration
            if (duration > 0) {
                val target = positionMs.coerceIn(0, duration - 1)
                pendingSeekTargetMs = target
                pendingSeekAtMs = SystemClock.elapsedRealtime()
                mp.seekTo(target)
            }
        }
    }

    /** 停止并释放当前播放。用户点"停止"时调用。 */
    fun stop() {
        if (player == null) return
        releaseCurrent()
        onPlayingChanged?.invoke(false)
    }

    /** 页面销毁时调用。 */
    fun release() {
        releaseCurrent()
    }

    private fun releaseCurrent() {
        val mp = player ?: return
        player = null
        pendingSeekTargetMs = null
        try {
            mp.setOnPreparedListener(null)
            mp.setOnCompletionListener(null)
            mp.setOnErrorListener(null)
            mp.setOnSeekCompleteListener(null)
            if (mp.isPlaying) mp.stop()
        } catch (e: Exception) {
            // 状态已经不对了，忽略
        }
        try {
            mp.reset()
        } catch (e: Exception) {
        }
        try {
            mp.release()
        } catch (e: Exception) {
        }
    }

    /**
     * 把语速应用到 MediaPlayer 实例上。
     *
     * 1.0x 且从未设过非默认值时直接跳过 —— 省一次无谓的驱动调用。
     * 但如果用户是从 1.5x 拖回 1.0x，就必须真的设回去，否则会一直停留在 1.5x。
     *
     * 设备不支持变速时（极老或残缺 ROM）提示一次并静默降级为原速。
     */
    private fun applySpeed(mp: MediaPlayer, value: Float) {
        if (value == 1.0f && !appliedNonDefaultSpeed) return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(value)
            appliedNonDefaultSpeed = value != 1.0f
        } catch (e: Exception) {
            if (!warnedUnsupported) {
                warnedUnsupported = true
                Toast.makeText(
                    context,
                    "当前设备不支持变速播放，将按原速播放",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
