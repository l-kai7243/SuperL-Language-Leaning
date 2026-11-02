package com.example.myapplication111.data

import android.content.Context
import java.io.File

/**
 * 收藏音频的定位与命名。
 *
 * 历史上音频文件名是 `<收藏 id>.wav`（硬编码扩展名，即使实际是 MP3 内容）。
 * 现在按真实容器格式命名（`.wav` / `.mp3` / `.ogg` / `.m4a`），
 * 同时保留对旧命名的兼容 —— 找不到新名字就回退扫 `<id>.` 前缀。
 *
 * 之所以不直接改 FavoriteItem 加一个格式字段：那样要写一次数据迁移，
 * 而"按前缀找文件"本身就是幂等且无需迁移的。
 */
object FavoritesAudioStore {

    /** 收藏的时间轴 sidecar 后缀（与音频同 id 前缀，随 [deleteFor] 一起清理）。 */
    private const val TIMELINE_SUFFIX = ".timeline.json"

    /** 优先按清单里记录的文件名找；找不到就扫描该收藏 id 前缀下唯一的音频文件。 */
    fun fileFor(context: Context, favoriteId: String, audioFileName: String): File? {
        val dir = AudioCache.favoritesAudioDir(context)

        if (audioFileName.isNotBlank()) {
            val exact = File(dir, audioFileName)
            if (exact.exists() && exact.length() > 0) return exact
        }

        // 兼容旧命名或被改过扩展名的情况（.json 是时间轴 sidecar，不是音频）
        val candidates = dir.listFiles { f ->
            f.isFile && f.length() > 0 &&
                f.name.startsWith("$favoriteId.") && !f.name.endsWith(".json")
        }
        return candidates?.firstOrNull()
    }

    /** 收藏时间轴 sidecar 的文件路径（<id>.timeline.json）。 */
    fun timelineFileFor(context: Context, favoriteId: String): File =
        File(AudioCache.favoritesAudioDir(context), "$favoriteId$TIMELINE_SUFFIX")

    /** 按音频格式给出收藏音频的文件名，如 `<id>.mp3`。 */
    fun fileNameFor(favoriteId: String, audioFormat: String): String =
        "$favoriteId.${extensionFor(audioFormat)}"

    /** 把服务商返回的格式字符串归一化为文件扩展名。 */
    private fun extensionFor(audioFormat: String): String = when (audioFormat.trim().lowercase()) {
        "mp3", "mpeg" -> "mp3"
        "ogg", "opus" -> "ogg"
        "flac" -> "flac"
        "aac", "m4a", "mp4" -> "m4a"
        else -> "wav"
    }

    /**
     * 删掉某个收藏的音频文件（所有该 id 前缀的文件都删）。
     * 供删除收藏、编辑文本后作废旧音频时使用。
     */
    fun deleteFor(context: Context, favoriteId: String, audioFileName: String) {
        val dir = AudioCache.favoritesAudioDir(context)
        if (audioFileName.isNotBlank()) {
            val exact = File(dir, audioFileName)
            if (exact.exists()) exact.delete()
        }
        dir.listFiles { f -> f.isFile && f.name.startsWith("$favoriteId.") }
            ?.forEach { if (it.exists()) it.delete() }
    }

    /**
     * 清理收藏目录里不被任何收藏引用的孤儿文件，返回清理数量。
     * 用户反复编辑文本会留下作废旧音频，这个顺手收尾。
     */
    fun pruneOrphans(context: Context): Int {
        val dir = AudioCache.favoritesAudioDir(context)
        val favorites = FavoritesRepository.load(context)
        val referenced = favorites
            .map { it.audioFileName }
            .filter { it.isNotBlank() }
            .toSet()
        val referencedIds = favorites.map { it.id }.toSet()

        val files: Array<File> = dir.listFiles() ?: return 0
        var removed = 0
        files.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.endsWith(TIMELINE_SUFFIX)) {
                // 时间轴 sidecar：所属收藏还在就保留（它的名字永远不在音频引用名单里）
                if (file.name.removeSuffix(TIMELINE_SUFFIX) in referencedIds) return@forEach
                if (file.delete()) removed++
                return@forEach
            }
            if (file.name in referenced) return@forEach
            if (file.delete()) removed++
        }
        return removed
    }
}
