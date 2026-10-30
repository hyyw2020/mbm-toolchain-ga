/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2017-2017 CSSlayer <wengxt@gmail.com>
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 * SPDX-FileComment: Modified from https://github.com/fcitx/libime/blob/1.0.14/src/libime/core/lrucache.h
 */
#ifndef FCITX5_ANDROID_INPUTCONTEXTCACHE_H
#define FCITX5_ANDROID_INPUTCONTEXTCACHE_H

#include <list>

#include <fcitx/inputcontext.h>

// A simple LRU cache.
class InputContextCache {
    typedef int key_type;
    typedef fcitx::InputContext content_type;
    typedef std::unique_ptr<content_type> value_type;
    typedef std::map<
            key_type,
            std::pair<value_type, typename std::list<key_type>::iterator>
    > dict_type;
    dict_type dict_;
    std::list<key_type> order_;
    // Maximum size of the cache.
    size_t sz_;

public:
    InputContextCache(size_t sz = 80) : sz_(sz) {}

    size_t size() const { return dict_.size(); }

    size_t capacity() const { return sz_; }

    bool empty() const { return dict_.empty(); }

    bool contains(const key_type &key) {
        return dict_.find(key) != dict_.end();
    }

    template<typename... Args>
    value_type *insert(const key_type &key, Args &&...args) {
        auto iter = dict_.find(key);
        if (iter == dict_.end()) {
            if (size() >= sz_) {
                evict();
            }

            order_.push_front(key);
            auto r = dict_.emplace(
                    key,
                    std::make_pair(value_type(std::forward<Args>(args)...), order_.begin())
            );
            return &r.first->second.first;
        }
        return nullptr;
    }

    void erase(const key_type &key) {
        auto i = dict_.find(key);
        if (i == dict_.end()) {
            return;
        }
        order_.erase(i->second.second);
        dict_.erase(i);
    }

    content_type *release(const key_type &key) {
        auto i = dict_.find(key);
        if (i == dict_.end()) {
            return nullptr;
        }
        auto content = i->second.first.release();
        order_.erase(i->second.second);
        dict_.erase(i);
        return content;
    }

    // find will refresh the item, so it is not const.
    value_type *find(const key_type &key) {
        // lookup value in the cache
        auto i = dict_.find(key);
        return find_helper(i);
    }

    template<class CompatibleKey, class CompatibleHash,
            class CompatiblePredicate>
    value_type *find(CompatibleKey const &k, CompatibleHash const &h,
                     CompatiblePredicate const &p) {
        return find_helper(dict_.find(k, h, p));
    }

    void clear() {
        dict_.clear();
        order_.clear();
    }

private:
    void evict() {
        if (order_.empty()) {
            return;
        }
        // N54：先取出键并从 order_ 移除迭代器，再销毁 InputContext。
        // 否则 dict_.erase 触发的 ~InputContext → releaseInputContext → icCache_.release()
        // 会再次 order_.erase / dict_.erase 当前迭代器，导致对已失效迭代器操作（UAF/双重释放/堆损坏）。
        // 由于已先把键移出 dict_，release() 内 dict_.find(key) 命中 end 会提前返回，从而断开重入。
        auto i = std::prev(order_.end());
        auto key = *i;
        order_.erase(i);
        dict_.erase(key);
    }

    value_type *find_helper(typename dict_type::iterator i) {
        if (i == dict_.end()) {
            // value not in cache
            return nullptr;
        }

        // return the value, but first update its place in the most
        // recently used list
        auto j = i->second.second;
        if (j != order_.begin()) {
            order_.splice(order_.begin(), order_, j, std::next(j));
            j = order_.begin();
            i->second.second = j;
        }
        return &i->second.first;
    }
};

#endif //FCITX5_ANDROID_INPUTCONTEXTCACHE_H
