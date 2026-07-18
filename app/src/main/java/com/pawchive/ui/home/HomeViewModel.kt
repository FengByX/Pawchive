package com.pawchive.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.data.api.ApiClient
import com.pawchive.data.api.CloudflareManager
import com.pawchive.data.model.Post
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val api = ApiClient.publicApi
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
                val morePosts = CloudflareManager.withClearance {
                    api.getRecentPosts(offset = currentOffset)
                }
                loadedPosts.addAll(morePosts)
                _posts.value = loadedPosts.toList()
                _hasMore.value = morePosts.size >= pageSize
            } catch (e: Exception) {
                currentOffset -= pageSize
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun fetchPosts(reset: Boolean) {
        if (_isLoading.value == true) return
        _isLoading.value = true
        _errorMessage.value = null

        if (reset) {
            currentOffset = 0
            loadedPosts.clear()
            _posts.value = emptyList()
        }

        viewModelScope.launch {
            try {
                val posts = CloudflareManager.withClearance {
                    api.getRecentPosts(offset = currentOffset)
                }
                loadedPosts.clear()
                loadedPosts.addAll(posts)
                _posts.value = loadedPosts.toList()
                _hasMore.value = posts.size >= pageSize
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}