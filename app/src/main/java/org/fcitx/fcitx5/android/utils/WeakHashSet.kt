/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.util.Collections
import java.util.WeakHashMap

/**
 * N76：线程安全的弱引用集合。
 *
 * 原实现直接返回 `Collections.newSetFromMap(WeakHashMap(...))`，底层是裸 WeakHashMap：
 *  - `ThemeManager.fireChange()` 在遍历监听器的同时，其它线程可能 add/remove；
 *  - `ClipboardManager` 在 `Dispatchers.Default` 下同样并发 fireUpdate；
 *  - WeakHashMap 还会被 GC 随时悄悄清掉条目。
 * 三者叠加 → `ConcurrentModificationException` 或监听器静默丢失。
 *
 * 这里用同一把锁串行化全部读写，并让 **遍历走快照**（回调里反注册自己也不会炸），
 * 对外仍是 `MutableSet<T>`，调用方零改动。
 */
@Suppress("FunctionName")
fun <T> WeakHashSet(): MutableSet<T> = SynchronizedWeakSet()

private class SynchronizedWeakSet<T> : MutableSet<T> {

    private val lock = Any()

    private val backing: MutableSet<T> = Collections.newSetFromMap(WeakHashMap<T, Boolean>())

    override val size: Int
        get() = synchronized(lock) { backing.size }

    override fun isEmpty(): Boolean = synchronized(lock) { backing.isEmpty() }

    override fun contains(element: T): Boolean = synchronized(lock) { backing.contains(element) }

    override fun containsAll(elements: Collection<T>): Boolean =
        synchronized(lock) { backing.containsAll(elements) }

    override fun add(element: T): Boolean = synchronized(lock) { backing.add(element) }

    override fun addAll(elements: Collection<T>): Boolean =
        synchronized(lock) { backing.addAll(elements) }

    override fun remove(element: T): Boolean = synchronized(lock) { backing.remove(element) }

    override fun removeAll(elements: Collection<T>): Boolean =
        synchronized(lock) { backing.removeAll(elements) }

    override fun retainAll(elements: Collection<T>): Boolean =
        synchronized(lock) { backing.retainAll(elements) }

    override fun clear() {
        synchronized(lock) { backing.clear() }
    }

    /**
     * N76：迭代基于**快照**——回调里 add/remove（监听器常见反注册写法）或 GC 清理弱引用，
     * 都不会让正在进行的遍历抛 ConcurrentModificationException。
     * 快照迭代器的 remove() 仍转发到真实集合，保持 MutableIterator 语义。
     */
    override fun iterator(): MutableIterator<T> {
        val snapshot = synchronized(lock) { backing.toList() }
        val delegate = snapshot.iterator()
        var last: T? = null
        return object : MutableIterator<T> {
            override fun hasNext(): Boolean = delegate.hasNext()
            override fun next(): T = delegate.next().also { last = it }
            override fun remove() {
                last?.let { synchronized(lock) { backing.remove(it) } }
                last = null
            }
        }
    }
}
