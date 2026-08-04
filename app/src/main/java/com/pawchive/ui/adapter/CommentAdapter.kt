package com.pawchive.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.data.model.Comment
import com.pawchive.databinding.ItemCommentBinding
import com.pawchive.utils.SafeHtmlHelper

class CommentAdapter : ListAdapter<Comment, CommentAdapter.CommentViewHolder>(DIFF_CALLBACK) {

    fun updateComments(newComments: List<Comment>) {
        submitList(newComments)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Comment>() {
            override fun areItemsTheSame(oldItem: Comment, newItem: Comment) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Comment, newItem: Comment) = oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CommentViewHolder(private val binding: ItemCommentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(comment: Comment) {
            binding.tvCommenter.text = "User #${comment.commenter}"
            binding.tvCommentDate.text = comment.published?.split("T")?.firstOrNull() ?: ""
            binding.tvCommentContent.text = SafeHtmlHelper.render(comment.content ?: "")
        }
    }
}
