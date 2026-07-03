package com.pawchive.data.api

import com.pawchive.data.model.Announcement
import com.pawchive.data.model.Comment
import com.pawchive.data.model.Creator
import com.pawchive.data.model.CreatorProfile
import com.pawchive.data.model.FanCard
import com.pawchive.data.model.FavoriteCreator
import com.pawchive.data.model.FavoritePost
import com.pawchive.data.model.FileSearchResult
import com.pawchive.data.model.Post
import com.pawchive.data.model.PostRevision
import com.pawchive.data.model.User
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PawchiveApi {

    // ============ 公开 API (v1) ============

    @GET("creators")
    suspend fun getCreators(): List<Creator>

    @GET("posts")
    suspend fun getRecentPosts(
        @Query("q") query: String? = null,
        @Query("o") offset: Int? = null
    ): List<Post>

    @GET("{service}/user/{creator_id}/profile")
    suspend fun getCreatorProfile(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String
    ): CreatorProfile

    @GET("{service}/user/{creator_id}")
    suspend fun getCreatorPosts(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Query("q") query: String? = null,
        @Query("o") offset: Int? = null
    ): List<Post>

    @GET("{service}/user/{creator_id}/post/{post_id}")
    suspend fun getPostDetails(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post_id") postId: String
    ): Post

    @GET("{service}/user/{creator_id}/post/{post_id}/comments")
    suspend fun getPostComments(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post_id") postId: String
    ): List<Comment>

    @GET("{service}/user/{creator_id}/post/{post_id}/revisions")
    suspend fun getPostRevisions(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post_id") postId: String
    ): List<PostRevision>

    @GET("{service}/user/{creator_id}/announcements")
    suspend fun getCreatorAnnouncements(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String
    ): List<Announcement>

    @GET("{service}/user/{creator_id}/fancards")
    suspend fun getCreatorFancards(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String
    ): List<FanCard>

    @GET("{service}/user/{creator_id}/links")
    suspend fun getCreatorLinks(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String
    ): List<CreatorProfile>

    @POST("{service}/user/{creator_id}/post/{post}/flag")
    suspend fun flagPost(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post") post: String
    ): Unit

    @GET("{service}/user/{creator_id}/post/{post}/flag")
    suspend fun isPostFlagged(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post") post: String
    ): Unit

    @GET("search_hash/{file_hash}")
    suspend fun searchByHash(
        @Path("file_hash") fileHash: String
    ): FileSearchResult

    // ============ 认证和收藏相关接口 (需要登录) ============

    /**
     * 获取收藏列表
     * @param type "post" 或 "artist"
     */
    @GET("account/favorites")
    suspend fun getFavorites(
        @Query("type") type: String
    ): List<Any>

    /**
     * 获取收藏的帖子列表
     */
    @GET("account/favorites")
    suspend fun getFavoritePosts(
        @Query("type") type: String = "post",
        @Query("o") offset: Int? = null
    ): List<FavoritePost>

    /**
     * 获取收藏的创作者列表
     */
    @GET("account/favorites")
    suspend fun getFavoriteCreators(
        @Query("type") type: String = "artist"
    ): List<FavoriteCreator>

    /**
     * 添加帖子到收藏
     */
    @POST("favorites/post/{service}/{creator_id}/{post_id}")
    suspend fun addPostToFavorites(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post_id") postId: String
    ): Response<Void>

    /**
     * 从收藏移除帖子
     */
    @DELETE("favorites/post/{service}/{creator_id}/{post_id}")
    suspend fun removePostFromFavorites(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String,
        @Path("post_id") postId: String
    ): Response<Void>

    /**
     * 添加创作者到收藏
     */
    @POST("favorites/creator/{service}/{creator_id}")
    suspend fun addCreatorToFavorites(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String
    ): Response<Void>

    /**
     * 从收藏移除创作者
     */
    @DELETE("favorites/creator/{service}/{creator_id}")
    suspend fun removeCreatorFromFavorites(
        @Path("service") service: String,
        @Path("creator_id") creatorId: String
    ): Response<Void>
}

/**
 * 登录 API 接口 - 使用不同的 base URL (网页端点)
 */
interface PawchiveLoginApi {
    @FormUrlEncoded
    @POST("account/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("location") location: String = ""
    ): Response<Void>
}
