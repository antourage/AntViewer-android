package com.antourage.weaverlib.screens.base.chat.rv

import androidx.recyclerview.widget.DiffUtil
import com.antourage.weaverlib.other.models.Message

internal class MessageListDiffUtilCallback(
    private val prevList: List<Message>,
    private val newList: List<Message>
) : DiffUtil.Callback() {
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        prevList[oldItemPosition].id == newList[newItemPosition].id &&
        prevList[oldItemPosition].nickname == newList[newItemPosition].nickname

    override fun getOldListSize() = prevList.size

    override fun getNewListSize() = newList.size

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        prevList[oldItemPosition] == newList[newItemPosition]
}