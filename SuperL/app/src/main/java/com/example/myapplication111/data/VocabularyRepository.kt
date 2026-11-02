package com.example.myapplication111.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 生词项。 */
data class VocabItem(
    val id: String,
    val word: String,
    val note: String,
    val createdAt: Long
)

/** 生词本数据的读写，JSON 数组存 SharedPreferences。 */
object VocabularyRepository {

    private const val PREFS_NAME = "ai_reader_prefs"
    private const val KEY_VOCAB = "vocab_list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<VocabItem> {
        val raw = prefs(context).getString(KEY_VOCAB, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<VocabItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VocabItem(
                        id = o.optString("id"),
                        word = o.optString("word"),
                        note = o.optString("note"),
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

    fun save(context: Context, list: List<VocabItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("word", item.word)
            o.put("note", item.note)
            o.put("createdAt", item.createdAt)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_VOCAB, arr.toString()).apply()
    }

    /** 新增一条生词（插入到列表头部）。 */
    fun add(context: Context, item: VocabItem) {
        val list = load(context).toMutableList()
        list.add(0, item)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == id }
        save(context, list)
    }
}
