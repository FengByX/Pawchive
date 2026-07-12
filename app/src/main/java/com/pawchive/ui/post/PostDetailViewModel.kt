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
                val videoList = extractVideoUrls(post)

                val comments = try {
                    api.getPostComments(service, creatorId, postId)
                } catch (e: Exception) {
                    emptyList()
                }

                val revisions = try {
                    api.getPostRevisions(service, creatorId, postId)
                } catch (e: Exception) {
                    emptyList()
                }

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
        val videoExtensions = listOf(".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".3gp", ".ts", ".flv", ".wmv", ".ogv", ".m4a")

        fun isVideo(path: String?, name: String?): Boolean {
            val lowerPath = path?.lowercase().orEmpty()
            val lowerName = name?.lowercase().orEmpty()
            return videoExtensions.any { ext ->
                lowerPath.endsWith(ext) || lowerName.endsWith(ext)
            }
        }

        val filePath = post.file?.path
        val fileName = post.file?.name
        if (isVideo(filePath, fileName)) {
            val fullUrl = "https://file.pawchive.pw/data${filePath.orEmpty()}"
            videoList.add(Pair(fullUrl, fileName ?: "video.mp4"))
        }

        val attachments = post.attachments
        if (!attachments.isNullOrEmpty()) {
            for (attachment in attachments) {
                if (isVideo(attachment.path, attachment.name)) {
                    val fullUrl = "https://file.pawchive.pw/data${attachment.path.orEmpty()}"
                    videoList.add(Pair(fullUrl, attachment.name ?: "video.mp4"))
                }
            }
        }

        return videoList
    }
}
