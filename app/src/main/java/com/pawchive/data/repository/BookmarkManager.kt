package com.pawchive.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.pawchive.data.model.Post
import com.google.gson.Gson

class BookmarkManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pawchive_bookmarks", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun bookmarkPost(post: Post) {
        val key = getPostKey(post.service, post.user, post.id)
        prefs.edit().putBoolean(key, true).apply()
        // Save post object for offline bookmark tab display
        val objectKey = "post_object_${post.service}_${post.user}_${post.id}"
        prefs.edit().putString(objectKey, gson.toJson(post)).apply()
    }

    fun unbookmarkPost(service: String, creatorId: String, postId: String) {
        val key = getPostKey(service, creatorId, postId)
        prefs.edit().remove(key).apply()
        val objectKey = "post_object_${service}_${creatorId}_$postId"
        prefs.edit().remove(objectKey).apply()
    }

    fun isPostBookmarked(service: String, creatorId: String, postId: String): Boolean {
        return prefs.getBoolean(getPostKey(service, creatorId, postId), false)
    }

    fun getBookmarkedPosts(): List<Post> {
        val list = mutableListOf<Post>()
        for (key in prefs.all.keys) {
            if (key.startsWith("post_object_")) {
                val json = prefs.getString(key, null)
                if (json != null) {
                    try {
                        list.add(gson.fromJson(json, Post::class.java))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
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

    private fun getCreatorKey(service: String, creatorId: String): String {
        return "creator_${service}_$creatorId"
    }
}
