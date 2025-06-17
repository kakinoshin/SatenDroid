package com.celstech.satendroid.selection

import com.celstech.satendroid.ui.models.LocalItem

/**
 * 選択モードの管理を担当するクラス
 */
class SelectionManager {

    /**
     * 選択モードに入る
     */
    fun enterSelectionMode(initialItem: LocalItem): SelectionState {
        return SelectionState(
            isSelectionMode = true,
            selectedItems = setOf(initialItem)
        )
    }

    /**
     * 選択モードを終了
     */
    fun exitSelectionMode(): SelectionState {
        return SelectionState(
            isSelectionMode = false,
            selectedItems = emptySet()
        )
    }

    /**
     * アイテムの選択状態を切り替え
     */
    fun toggleItemSelection(
        currentSelection: Set<LocalItem>,
        item: LocalItem
    ): Set<LocalItem> {
        return if (currentSelection.contains(item)) {
            currentSelection - item
        } else {
            currentSelection + item
        }
    }

    /**
     * 全選択
     */
    fun selectAll(allItems: List<LocalItem>): Set<LocalItem> {
        return allItems.toSet()
    }

    /**
     * 全選択解除
     */
    fun deselectAll(): Set<LocalItem> {
        return emptySet()
    }

    /**
     * 全選択か部分選択かを判定
     */
    fun getSelectionStatus(
        selectedItems: Set<LocalItem>,
        allItems: List<LocalItem>
    ): SelectionStatus {
        return when {
            selectedItems.isEmpty() -> SelectionStatus.None
            selectedItems.size == allItems.size -> SelectionStatus.All
            else -> SelectionStatus.Partial
        }
    }

    /**
     * 選択状態データ
     */
    data class SelectionState(
        val isSelectionMode: Boolean,
        val selectedItems: Set<LocalItem>
    )

    /**
     * 選択状況の列挙
     */
    enum class SelectionStatus {
        None, Partial, All
    }
}
