package com.pawchive.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.pawchive.data.model.Post
import com.google.gson.Gson

class BookmarkManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pawchive_bookmarks", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 收藏顺序记录：以 "k1|k2|..." 形式存储，按添加顺序追加。
    // SharedPreferences 的 all.keys 返回 Set，顺序不稳定，
    // 直接遍历会导致收藏列表顺序在每次冷启动后跳变。
    private val orderedPostKeysKey = "ordered_post_object_keys"
    private val separator = "|"

    fun bookmarkPost(post: Post) {
        val key = getPostKey(post.service, post.user, post.id)
        val objectKey = getPostObjectKey(post.service, post.user, post.id)

        prefs.edit()
            .putBoolean(key, true)
            .putString(objectKey, gson.toJson(post))
            .apply()

        // 追加到顺序记录（若已存在则不动，保持原顺序）
        appendOrderedKey(objectKey)
    }

    fun unbookmarkPost(service: String, creatorId: String, postId: String) {
        val key = getPostKey(service, creatorId, postId)
        val objectKey = getPostObjectKey(service, creatorId, postId)

        prefs.edit()
            .remove(key)
            .remove(objectKey)
            .apply()

        removeFromOrdered(objectKey)
    }

    fun isPostBookmarked(service: String, creatorId: String, postId: String): Boolean {
        return prefs.getBoolean(getPostKey(service, creatorId, postId), false)
    }

    /**
     * 返回所有已收藏的 Post，顺序为添加顺序（最早的在前）。
     * 对于历史数据（无顺序记录的旧收藏），自动迁移追加到末尾。
     */
    fun getBookmarkedPosts(): List<Post> {
        val orderedKeys = getOrderedKeys().toMutableList()

        // 迁移：把 prefs 中存在但没有顺序记录的旧 post_object_ key 补进列表末尾
        val orphanKeys = prefs.all.keys
            .filter { it.startsWith("post_object_") }
            .filter { it !in orderedKeys }
        if (orphanKeys.isNotEmpty()) {
            orderedKeys.addAll(orphanKeys)
            saveOrderedKeys(orderedKeys)
        }

        val list = mutableListOf<Post>()
        for (key in orderedKeys) {
            val json = prefs.getString(key, null) ?: continue
            try {
                list.add(gson.fromJson(json, Post::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return list
    }

    fun bookmarkCreator(service: String, creatorId: String) {
        val key = getCreatorKey(service, creatorId)
        prefs.edit().putBoolean(key, true).apply()
    }

    fun unbookmarkCreator(service: String, creatorId: String) {
        val key = getCreatorKey(service, creatorId)
        prefs.edit().remove(key).apply()
    }

    fun isCreatorBookmarked(service: String, creatorId: String): Boolean {
        return prefs.getBoolean(getCreatorKey(service, creatorId), false)
    }

    private fun getPostKey(service: String, creatorId: String, postId: String): String {
        return "post_${service}_${creatorId}_$postId"
    }

    private fun getPostObjectKey(service: String, creatorId: String, postId: String): String {
        return "post_object_${service}_${creatorId}_$postId"
    }

    private fun getCreatorKey(service: String, creatorId: String): String {
        return "creator_${service}_$creatorId"
    }

    private fun getOrderedKeys(): List<String> {
        val raw = prefs.getString(orderedPostKeysKey, "") ?: ""
        return raw.split(separator).filter { it.isNotEmpty() }
    }

    private fun saveOrderedKeys(keys: List<String>) {
        prefs.edit().putString(orderedPostKeysKey, keys.joinToString(separator)).apply()
    }

    private fun appendOrderedKey(key: String) {
        val current = getOrderedKeys()
        if (key !in current) {
            saveOrderedKeys(current + key)
        }
    }

    private fun removeFromOrdered(key: String) {
        val current = getOrderedKeys()
        if (key in current) {
            saveOrderedKeys(current - key)
        }
    }
}
