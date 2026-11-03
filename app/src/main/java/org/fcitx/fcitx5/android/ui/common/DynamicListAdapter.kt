/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import java.util.Collections

abstract class DynamicListAdapter<T>(
    initialEntries: List<T>,
    val enableAddAndDelete: Boolean = true,
    val enableOrder: Boolean = false,
    val initCheckBox: (CheckBox.(T) -> Unit) = { visibility = View.GONE },
    var initEditButton: (ImageButton.(T) -> Unit) = { visibility = View.GONE },
    var initSettingsButton: (ImageButton.(T) -> Unit) = { visibility = View.GONE },
    var initPinButton: (ImageButton.(T) -> Unit) = { visibility = View.GONE }
) :
    RecyclerView.Adapter<DynamicListAdapter<T>.ViewHolder>() {

    /**
     * 真源数据（所有增删改都先落这里）。
     * _shown 是其「经过滤器后的视图」——过滤为空时二者恒等，因此对未使用过滤的界面行为完全不变。
     * 这样实现搜索/筛选时绝不会丢数据（保存时永远以 _all 为准）。
     */
    private val _all = initialEntries.toMutableList()
    private val _shown = _all.toMutableList()

    private var filter: ((T) -> Boolean)? = null

    val entries: List<T>
        get() = _shown

    /** 全部条目（不受过滤影响），保存词库等场景必须用这个，避免只存了过滤后的子集。 */
    fun allItems(): List<T> = _all.toList()

    /** 设置过滤谓词：null = 显示全部。切换会重算 _shown 并刷新。 */
    fun setFilter(predicate: ((T) -> Boolean)?) {
        filter = predicate
        recomputeShown()
        notifyDataSetChanged()
    }

    fun clearFilter() = setFilter(null)

    private fun recomputeShown() {
        _shown.clear()
        val pred = filter
        if (pred == null) _shown.addAll(_all)
        else _shown.addAll(_all.filter(pred))
    }

    var multiselect = false
        private set

    private val selected = mutableListOf<T>()

    private var listener: OnItemChangedListener<T>? = null

    fun removeItemChangedListener() {
        listener = null
    }

    fun addOnItemChangedListener(x: OnItemChangedListener<T>) {
        listener = listener?.let { OnItemChangedListener.merge(it, x) } ?: x
    }

    protected var itemTouchHelper: ItemTouchHelper? = null

    var removable: (T) -> Boolean = { true }

    private var onBackPressedCallback: OnBackPressedCallback? = null

    abstract fun showEntry(x: T): String

    /**
     * 副文本（可选）。默认返回 null，隐藏副文本行。
     * 子类可覆写以展示次要信息（如自定义词组的「键 / 拼音」）。
     */
    open fun showSubEntry(x: T): String? = null

    private var mainViewModel: MainViewModel? = null

    fun setViewModel(model: MainViewModel) {
        mainViewModel = model
    }

    inner class ViewHolder(entryUi: DynamicListEntryUi) : RecyclerView.ViewHolder(entryUi.root) {
        val multiselectCheckBox = entryUi.multiselectCheckBox
        val handleImage = entryUi.handleImage
        val checkBox = entryUi.checkBox
        val nameText = entryUi.nameText
        val subText = entryUi.subText
        val editButton = entryUi.editButton
        val settingsButton = entryUi.settingsButton
        val pinButton = entryUi.pinButton
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(DynamicListEntryUi(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = entries[position]
        with(holder) {
            handleImage.setOnLongClickListener {
                if (!multiselect && removable(item))
                    itemTouchHelper
                        ?.startDrag(this)
                        ?.run { true }
                        ?: false
                else false
            }
            nameText.text = showEntry(item)

            val sub = showSubEntry(item)
            if (sub.isNullOrBlank()) {
                subText.visibility = View.GONE
            } else {
                subText.visibility = View.VISIBLE
                subText.text = sub
            }

            if (multiselect) {
                handleImage.visibility = View.GONE
                multiselectCheckBox.visibility = View.VISIBLE
                checkBox.visibility = View.GONE
                settingsButton.visibility = View.GONE
                editButton.visibility = View.GONE
                pinButton.visibility = View.GONE
            } else {
                handleImage.visibility = if (enableOrder) View.VISIBLE else View.GONE
                multiselectCheckBox.visibility = View.GONE
                checkBox.visibility = View.VISIBLE
                settingsButton.visibility = View.VISIBLE
                editButton.visibility = View.VISIBLE
                pinButton.visibility = View.VISIBLE
                initCheckBox(checkBox, item)
                initSettingsButton(settingsButton, item)
                initEditButton(editButton, item)
                initPinButton(pinButton, item)
            }

            multiselectCheckBox.isChecked = item in selected

            if (enableAddAndDelete && removable(item)) {
                multiselectCheckBox.setOnCheckedChangeListener { _, isChecked ->
                    select(item, isChecked)
                }
                nameText.setOnLongClickListener {
                    itemTouchHelper?.startDrag(holder)
                    true
                }
                nameText.setOnClickListener {
                    multiselectCheckBox.toggle()
                }
            } else {
                multiselectCheckBox.isEnabled = false
                multiselectCheckBox.setOnCheckedChangeListener(null)
                nameText.setOnClickListener(null)
            }
        }
    }

    override fun onViewRecycled(holder: DynamicListAdapter<T>.ViewHolder) {
        // reset view state and remove listeners once recycled
        holder.apply {
            multiselectCheckBox.setOnCheckedChangeListener(null)
            multiselectCheckBox.isEnabled = true
            multiselectCheckBox.isChecked = false
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isEnabled = true
            checkBox.isChecked = false
            nameText.setOnClickListener(null)
            settingsButton.setOnClickListener(null)
            editButton.setOnClickListener(null)
            pinButton.setOnClickListener(null)
        }
    }

    private fun select(item: T, shouldSelect: Boolean) {
        if (!enableAddAndDelete || !multiselect)
            return
        if (shouldSelect) {
            if (selected.indexOf(item) == -1) selected.add(item)
        } else {
            selected.remove(item)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @CallSuper
    open fun enterMultiSelect(onBackPressedDispatcher: OnBackPressedDispatcher) {
        mainViewModel?.let {
            if (multiselect)
                return
            onBackPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    exitMultiSelect()
                }
            }
            onBackPressedDispatcher.addCallback(onBackPressedCallback!!)
            it.toolbarButton.value = ButtonMode.DELETE
            multiselect = true
            notifyDataSetChanged()
        }
    }


    internal fun deleteSelected() {
        if (!multiselect || selected.isEmpty())
            return
        val indexed = selected.mapNotNull { entry ->
            indexItem(entry).takeIf { it != -1 }?.let { it to entry }
        }.sortedByDescending { it.first }
        indexed.forEach { (_, entry) ->
            _all.remove(entry)
        }
        selected.clear()
        recomputeShown()
        notifyDataSetChanged()
        listener?.onItemRemovedBatch(indexed)
    }

    @SuppressLint("NotifyDataSetChanged")
    @CallSuper
    open fun exitMultiSelect() {
        mainViewModel?.let {
            if (!multiselect)
                return
            onBackPressedCallback?.remove()
            multiselect = false
            selected.clear()
            notifyDataSetChanged()
            it.toolbarButton.value = if (entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        }
    }

    override fun getItemCount(): Int = _shown.size

    @CallSuper
    open fun addItem(idx: Int = _all.size, item: T) {
        val realIdx = idx.coerceIn(0, _all.size)
        _all.add(realIdx, item)
        recomputeShown()
        notifyDataSetChanged()
        listener?.onItemAdded(realIdx, item)
        mainViewModel?.toolbarButton?.value = ButtonMode.EDIT
    }

    @CallSuper
    open fun removeItem(idx: Int): T {
        val item = _shown.getOrNull(idx) ?: _all.first()
        _all.remove(item)
        recomputeShown()
        notifyDataSetChanged()
        listener?.onItemRemoved(idx, item)
        if (_all.isEmpty())
            mainViewModel?.toolbarButton?.value = ButtonMode.NONE
        return item
    }

    @CallSuper
    open fun swapItem(fromIdx: Int, toIdx: Int) {
        if (fromIdx in _all.indices && toIdx in _all.indices) {
            Collections.swap(_all, fromIdx, toIdx)
        }
        recomputeShown()
        notifyDataSetChanged()
        val item = _all.getOrNull(toIdx) ?: return
        listener?.onItemSwapped(fromIdx, toIdx, item)
    }

    @CallSuper
    open fun updateItem(idx: Int, item: T) {
        val old = _shown.getOrNull(idx) ?: return
        val allIdx = _all.indexOf(old)
        if (allIdx != -1) _all[allIdx] = item else _all.add(item)
        recomputeShown()
        notifyDataSetChanged()
        listener?.onItemUpdated(idx, old, item)
    }

    fun indexItem(item: T): Int = _shown.indexOf(item)
}