package com.example.myapplication111.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏项。
 * @param id 唯一标识（时间戳 + UUID）
 * @param title 标题（默认取文本前 20 字）
 * @param text 全文
 * @param audioFileName 对应音频文件名（位于 favorites_audio 目录，为空表示无音频）
 * @param createdAt 创建时间戳
 */
data class FavoriteItem(
    val id: String,
    val title: String,
    val text: String,
    val audioFileName: String,
    val createdAt: Long
)

/**
 * 收藏数据的读写。列表以 JSON 数组字符串存进 SharedPreferences，
 * 音频文件存 filesDir/favorites_audio（与 tts_cache 分开，避免清缓存误删）。
 */
object FavoritesRepository {

    private const val PREFS_NAME = "ai_reader_prefs"
    private const val KEY_FAVORITES = "favorites_list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<FavoriteItem> {
        val raw = prefs(context).getString(KEY_FAVORITES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<FavoriteItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    FavoriteItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        text = o.optString("text"),
                        audioFileName = o.optString("audioFileName"),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun save(context: Context, list: List<FavoriteItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("title", item.title)
            o.put("text", item.text)
            o.put("audioFileName", item.audioFileName)
            o.put("createdAt", item.createdAt)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    /** 新增一条收藏（插入到列表头部，最新在前）。 */
    fun add(context: Context, item: FavoriteItem) {
        val list = load(context).toMutableList()
        list.add(0, item)
        save(context, list)
    }

    /** 原地更新一条收藏。 */
    fun update(context: Context, item: FavoriteItem) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            list[idx] = item
            save(context, list)
        }
    }

    /** 删除指定收藏，同时删除它的音频文件（含历史命名留下的副本）。 */
    fun delete(context: Context, id: String) {
        val list = load(context).toMutableList()
        val target = list.firstOrNull { it.id == id }
        if (target != null) {
            FavoritesAudioStore.deleteFor(context, target.id, target.audioFileName)
        }
        list.removeAll { it.id == id }
        save(context, list)
    }
}
