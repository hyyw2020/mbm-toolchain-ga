/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import java.util.concurrent.ConcurrentHashMap

class PickerPagesAdapter(
    val theme: Theme,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener,
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    recentlyUsedFileName: String,
    private val bordered: Boolean,
    private val policy: PickerPolicy
) : RecyclerView.Adapter<PickerPagesAdapter.ViewHolder>() {

    class ViewHolder(val ui: PickerPageUi) : RecyclerView.ViewHolder(ui.root)

    /**
     * list<`Category` to `[start, end]`>, starting with empty "RecentlyUsed" category
     */
    private val categories: MutableList<Pair<PickerData.Category, IntRange>> = mutableListOf(
        PickerData.RecentlyUsedCategory to IntRange(0, 0)
    )

    /**
     * list<page of symbols>, starting with empty "RecentlyUsed" page
     */
    private val pages: MutableList<List<String>> = mutableListOf(listOf())

    /** 后台构造出的分类与分页结果，不含开头的 "RecentlyUsed" 页 */
    private class BuiltPages(
        val categories: List<Pair<PickerData.Category, IntRange>>,
        val pages: List<List<String>>
    )

    // N40：EmojiPickerPolicy.filter 会跑 hasGlyph（约 1500 次），放到后台线程构造，
    // 否则首次打开 emoji 面板会阻塞主线程数百毫秒。
    private val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var buildJob: Job? = null
    /** N40：构造结果缓存，同一个 invalidateKey 只构造一次 */
    private val builtCache = ConcurrentHashMap<Any, BuiltPages>()

    private fun buildPages(data: List<Pair<PickerData.Category, Array<String>>>): BuiltPages {
        val cats = ArrayList<Pair<PickerData.Category, IntRange>>(data.size)
        val pgs = ArrayList<List<String>>()
        data.forEach { (cat, arr) ->
            val list = arr.filter(policy::filter)
            val chunks = list.chunked(density.pageSize)
            // +1：第 0 页固定是 "RecentlyUsed" 页
            cats.add(cat to IntRange(pgs.size + 1, pgs.size + chunks.size))
            pgs.addAll(chunks)
        }
        return BuiltPages(cats, pgs)
    }

    /**
     * N40：占位数据 —— 只按未过滤数据分出页码（不跑 filter，开销可忽略），内容留空。
     * 这样 UI 立刻拿到合法的分类/页码索引（分页指示器不错位），真正内容由后台构造后切换。
     */
    private fun applyPlaceholders() {
        categories.clear()
        // empty "RecentlyUsed" category
        categories.add(PickerData.RecentlyUsedCategory to IntRange(0, 0))
        pages.clear()
        // empty "RecentlyUsed" page
        pages.add(emptyList())
        rawData.forEach { (cat, arr) ->
            val pageCount = arr.asList().chunked(density.pageSize).size
            categories.add(cat to IntRange(pages.size, pages.size + pageCount - 1))
            repeat(pageCount) { pages.add(emptyList()) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyBuilt(built: BuiltPages) {
        categories.clear()
        // empty "RecentlyUsed" category
        categories.add(PickerData.RecentlyUsedCategory to IntRange(0, 0))
        categories.addAll(built.categories)
        pages.clear()
        // empty "RecentlyUsed" page
        pages.add(emptyList())
        pages.addAll(built.pages)
        notifyDataSetChanged()
    }

    private fun launchBuild(key: Any) {
        // N40：先取消上一次未完成的构造，避免旧结果覆盖新结果
        buildJob?.cancel()
        buildJob = buildScope.launch {
            val built = builtCache[key] ?: buildPages(rawData).also { builtCache[key] = it }
            // 数据变更与刷新必须回到主线程
            withContext(Dispatchers.Main.immediate) {
                applyBuilt(built)
            }
        }
    }

    init {
        applyPlaceholders()
        launchBuild(policy.invalidateKey())
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuildCategories() {
        applyPlaceholders()
        notifyDataSetChanged()
        launchBuild(lastInvalidateKey)
    }

    private var lastInvalidateKey = policy.invalidateKey()

    @SuppressLint("NotifyDataSetChanged")
    fun refreshIfNeeded() {
        val newKey = policy.invalidateKey()
        if (lastInvalidateKey != newKey) {
            lastInvalidateKey = newKey
            rebuildCategories()
            notifyDataSetChanged()
        }
    }

    private val recentlyUsed = RecentlyUsed(recentlyUsedFileName, density.pageSize)

    fun insertRecent(text: String) {
        if (text.length == 1 && text[0].code.let { it in Digit || it in FullWidthDigit }) return
        recentlyUsed.insert(text)
    }

    fun getCategoryList(): List<PickerData.Category> {
        return categories.map { it.first }
    }

    fun getCategoryIndexOfPage(page: Int): Int {
        return categories.indexOfFirst { page in it.second }
    }

    fun getCategoryRangeOfPage(page: Int): IntRange {
        return categories.find { page in it.second }?.second ?: IntRange(0, 0)
    }

    fun getRangeOfCategoryIndex(cat: Int): IntRange {
        return categories[cat].second
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(PickerPageUi(parent.context, theme, density, bordered))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position == 0) {
            // RecentlyUsed content should be displayed as-is, without popups
            holder.ui.setItems(recentlyUsed.items)
        } else {
            holder.ui.setItems(pages[position], policy)
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.ui.keyActionListener = keyActionListener
        holder.ui.popupActionListener = if (holder.bindingAdapterPosition == 0) {
            // prevent popup on RecentlyUsed page
            null
        } else {
            popupActionListener
        }
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.ui.keyActionListener = null
        holder.ui.popupActionListener = null
    }

    companion object {
        private val Digit = IntRange('0'.code, '9'.code)
        private val FullWidthDigit = IntRange('０'.code, '９'.code)
    }
}