package com.pawchive.data.model

import com.google.gson.annotations.SerializedName

data class Creator(
    val id: String,
    val name: String,
    val service: String,
    val favorited: Int?,
    val indexed: Long?,
    val updated: Long?
)

data class CreatorProfile(
    val id: String,
    @SerializedName("public_id") val publicId: String?,
    val service: String,
    val name: String,
    val indexed: String?,
    val updated: String?
)

data class PostFile(
    val name: String?,
    val path: String?
)

data class Attachment(
    val name: String?,
    val path: String?
)

data class Post(
    val id: String,
    val user: String,
    @SerializedName("user_name") val userName: String?,
    val service: String,
    val title: String?,
    val content: String?,
    val added: String?,
    val published: String?,
    val edited: String?,
    val file: PostFile?,
    val attachments: List<Attachment>?,
    @SerializedName("shared_file") val sharedFile: Boolean?,
    val next: String? = null,
    val prev: String? = null
)

data class PostRevision(
    @SerializedName("revision_id") val revisionId: Int,
    val id: String,
    val user: String,
    val service: String,
    val title: String?,
    val content: String?,
    val added: String?,
    val published: String?,
    val edited: String?,
    val file: PostFile?,
    val attachments: List<Attachment>?
)

data class CommentRevision(
    val id: Int,
    val content: String?,
    val added: String?
)

data class Comment(
    val id: String,
    @SerializedName("parent_id") val parentId: String?,
    val commenter: String,
    val content: String?,
    val published: String?,
    val revisions: List<CommentRevision>?
)

data class Announcement(
    val service: String,
    @SerializedName("user_id") val userId: String,
    val hash: String,
    val content: String?,
    val added: String?
)

data class FanCard(
    val id: Long,
    @SerializedName("user_id") val userId: String,
    @SerializedName("file_id") val fileId: Long,
    val hash: String,
    val mime: String,
    val ext: String,
    val added: String,
    val size: Long
)

data class FileSearchResult(
    val id: Long,
    val hash: String,
    val mime: String,
    val ext: String,
    val size: Long,
    val posts: List<FileSearchPost>?,
    @SerializedName("discord_posts") val discordPosts: List<FileSearchDiscordPost>?
)

data class FileSearchPost(
    @SerializedName("file_id") val fileId: Long,
    val id: String,
    val user: String,
    val service: String,
    val title: String?,
    val substring: String?,
    val published: String?,
    val file: PostFile?,
    val attachments: List<Attachment>?
)

data class FileSearchDiscordPost(
    @SerializedName("file_id") val fileId: Long,
    val id: String,
    val server: String,
    val channel: String,
    val substring: String?,
    val published: String?
)

// 用户和收藏相关模型
data class User(
    val id: Int,
    val name: String,
    @SerializedName("favorites_count") val favoritesCount: Int?,
    @SerializedName("followed_creators_count") val followedCreatorsCount: Int?
)

data class FavoritePost(
    val id: String,
    val user: String,
    val service: String,
    val title: String?,
    val content: String?,
    val added: String?,
    val published: String?,
    val edited: String?,
    val file: PostFile?,
    val attachments: List<Attachment>?,
    @SerializedName("shared_file") val sharedFile: Boolean?,
    @SerializedName("faved_seq") val favedSeq: Int?
)

data class FavoriteCreator(
    val id: String,
    val name: String,
    val service: String,
    @SerializedName("faved_seq") val favedSeq: Int?,
    val indexed: String?,
    val updated: String?,
    @SerializedName("last_imported") val lastImported: String?
)
