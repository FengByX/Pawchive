package com.pawchive.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Comment
import com.pawchive.data.model.Post
import com.pawchive.data.model.PostRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PostDetailUiState(
    val post: Post? = null,
    val comments: List<Comment> = emptyList(),
    val revisions: List<PostRevision> = emptyList(),
    val isBookmarked: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val videoList: List<Pair<String, String>> = emptyList(),
    val currentVideoIndex: Int = 0
)

class PostDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    private val api = ApiClient.publicApi

    fun loadPostDetails(service: String, creatorId: String, postId: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val post = api.getPostDetails(service, creatorId, postId)
                val comments = api.getPostComments(service, creatorId, postId)
                val revisions = api.getPostRevisions(service, creatorId, postId)

                val videoList = extractVideoUrls(post)

                _uiState.value = _uiState.value.copy(
                    post = post,
                    comments = comments,
                    revisions = revisions,
                    videoList = videoList,
                    isLoading = false,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun setCurrentVideoIndex(index: Int) {
        _uiState.value = _uiState.value.copy(currentVideoIndex = index)
    }

    fun setBookmarked(isBookmarked: Boolean) {
        _uiState.value = _uiState.value.copy(isBookmarked = isBookmarked)
    }

    private fun extractVideoUrls(post: Post): List<Pair<String, String>> {
        val videoList = mutableListOf<Pair<String, String>>()
        val videoExtensions = listOf(".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".3gp", ".ts")

        val filePath = post.file?.path
        if (!filePath.isNullOrEmpty()) {
            if (videoExtensions.any { filePath.lowercase().endsWith(it) }) {
                val fullUrl = "https://file.pawchive.st/data$filePath"
                videoList.add(Pair(fullUrl, post.file?.name ?: "video.mp4"))
            }
        }

        val attachments = post.attachments
        if (!attachments.isNullOrEmpty()) {
            for (attachment in attachments) {
                val path = attachment.path
                if (videoExtensions.any { path.lowercase().endsWith(it) }) {
                    val fullUrl = "https://file.pawchive.st/data$path"
                    videoList.add(Pair(fullUrl, attachment.name ?: "video.mp4"))
                }
            }
        }

        return videoList
    }
}
