package com.pawchive.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.common.databinding.ItemSearchHistoryBinding

class SearchHistoryAdapter(
    private val onItemClicked: (String) -> Unit,
    private val onDeleteClicked: (String) -> Unit
) : ListAdapter<String, SearchHistoryAdapter.SearchHistoryViewHolder>(DIFF_CALLBACK) {

    fun updateItems(newItems: List<String>) {
        submitList(newItems)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchHistoryViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SearchHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchHistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SearchHistoryViewHolder(private val binding: ItemSearchHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(query: String) {
            binding.tvHistoryItem.text = query
            binding.root.setOnClickListener { onItemClicked(query) }
            binding.btnDeleteHistory.setOnClickListener { onDeleteClicked(query) }
        }
    }
}
