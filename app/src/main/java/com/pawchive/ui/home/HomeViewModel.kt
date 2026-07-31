package com.pawchive.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Post
import com.pawchive.data.repository.BlockedCreatorManager
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val api = ApiClient.publicApi
    private val blockedCreatorManager = BlockedCreatorManager(application)
    // 服务端 /posts 接口固定每页返回 50 条（仅支持 offset，无 limit 参数），
    // 因此此处与服务端页大小保持一致，用于判断是否还有下一页。
    private val pageSize = 50

    private val loadedPosts = mutableListOf<Post>()
    private var currentOffset = 0

    private val _posts = MutableLiveData<List<Post>>(emptyList())
    val posts: LiveData<List<Post>> = _posts

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _hasMore = MutableLiveData(false)
    val hasMore: LiveData<Boolean> = _hasMore

    fun loadInitial() {
        if (loadedPosts.isNotEmpty()) return
        fetchPosts(reset = true)
    }

    fun refresh() {
        fetchPosts(reset = true)
    }

    fun loadMore() {
        if (_isLoading.value == true) return
        _isLoading.value = true
        _errorMessage.value = null
        currentOffset += pageSize

        viewModelScope.launch {
            try {
                val morePosts = api.getRecentPosts(offset = currentOffset)
                loadedPosts.addAll(morePosts)
                _posts.value = filterBlocked(loadedPosts)
                _hasMore.value = morePosts.size >= pageSize
            } catch (e: Exception) {
                currentOffset -= pageSize
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 屏蔽状态变化后重新过滤已加载的数据（无需重新请求网络）
     */
    fun refreshBlockedFilter() {
        if (loadedPosts.isNotEmpty()) {
            _posts.value = filterBlocked(loadedPosts)
        }
    }

    private fun fetchPosts(reset: Boolean) {
        if (_isLoading.value == true) return
        _isLoading.value = true
        _errorMessage.value = null

        if (reset) {
            currentOffset = 0
        }

        viewModelScope.launch {
            try {
                val posts = api.getRecentPosts(offset = currentOffset)
                if (reset) {
                    loadedPosts.clear()
                }
                loadedPosts.addAll(posts)
                _posts.value = filterBlocked(loadedPosts)
                _hasMore.value = posts.size >= pageSize
            } catch (e: Exception) {
                if (reset) {
                    currentOffset = 0
                }
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 过滤掉被屏蔽创作者的帖子
     */
    private fun filterBlocked(posts: List<Post>): List<Post> {
        return posts.filter { !blockedCreatorManager.isCreatorBlocked(it.service, it.user) }
    }
}