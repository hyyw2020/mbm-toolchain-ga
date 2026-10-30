/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_NATIVESTREAMBUF_H
#define FCITX5_ANDROID_NATIVESTREAMBUF_H

#include <array>
#include <mutex>
#include <streambuf>

#include <android/log.h>

template<std::size_t SIZE = 128>
class native_streambuf : public std::streambuf {
    static_assert(SIZE >= 2, "SIZE must leave room for the terminating '\\0'");
public:
    using Base = std::streambuf;
    using char_type = typename Base::char_type;
    using int_type = typename Base::int_type;

    native_streambuf() : _buffer{} {
        // N125：故意不设 put 区。这样基类的 sputc / xsputn 无法在锁外用 pbump 搬动 pptr，
        // 所有写入都必须经由 overflow() / xsputn()，从而统一在 mutex_ 保护下更新写指针。
        // 否则并发 pbump 的丢失更新会把 pptr 推过 epptr，随后的 *pptr() = '\0' 即越界写。
        Base::setp(nullptr, nullptr);
    }

    // buffer is full but current "line" of log hasn't finished
    int_type overflow(int_type ch) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!Base::traits_type::eq_int_type(ch, Base::traits_type::eof())) {
            append(Base::traits_type::to_char_type(ch));
        }
        return 0;
    }

    // current "line" of log has finished, and buffer is not full
    int_type sync() override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (pos_ > 0) flush_line();
        // this "line" has finished and written to android log, reset state for next "line"
        should_offset = true;
        return 0;
    }

protected:
    std::streamsize xsputn(const char_type *s, std::streamsize n) override {
        // N125：与 overflow 共用同一把锁，整段写入只加锁一次
        std::lock_guard<std::mutex> lock(mutex_);
        for (std::streamsize i = 0; i < n; i++) {
            append(s[i]);
        }
        return n;
    }

private:
    std::array<char_type, SIZE> _buffer;
    android_LogPriority prio = ANDROID_LOG_VERBOSE;
    /**
     * whether the first character in buffer represents log level or not
     */
    bool should_offset = true;
    /**
     * N125：保护 _buffer / pos_ / prio / should_offset。本对象是 fcitx 的全局日志流，
     * addon loader、libime、事件线程会并发向它写日志。
     */
    std::mutex mutex_;
    /**
     * 当前行已缓冲的字符数，末位恒留给 '\0'，故取值范围为 [0, SIZE-1]
     */
    std::size_t pos_ = 0;

    void append(char_type c) {
        // 写满（扣掉 '\0' 位）时先把这一段刷进 android log，保证不出界
        if (pos_ >= SIZE - 1) flush_line();
        _buffer[pos_++] = c;
    }

    void flush_line() {
        // pos_ 恒 <= SIZE-1，_buffer[SIZE-1] 是预留的终止位
        _buffer[pos_] = '\0';
        const char_type *text = _buffer.data();
        if (should_offset) {
            // it's the first write of this "line", guess priority
            update_log_priority(text[0]);
        }
        // this write would skip first character if it carries the log level
        write_log(text);
        // consequence write of this "line" should use same priority and should not skip characters
        should_offset = false;
        pos_ = 0;
    }

    void update_log_priority(const char_type first) {
        switch (first) {
            case 'D':
                prio = ANDROID_LOG_DEBUG;
                break;
            case 'I':
                prio = ANDROID_LOG_INFO;
                break;
            case 'W':
                prio = ANDROID_LOG_WARN;
                break;
            case 'E':
                prio = ANDROID_LOG_ERROR;
                break;
            case 'F':
                prio = ANDROID_LOG_FATAL;
                break;
            default:
                prio = ANDROID_LOG_VERBOSE;
                break;
        }
    }

    void write_log(const char_type *text) const {
        __android_log_write(prio, "fcitx5", text + (should_offset ? 1 : 0));
    }
};

#endif //FCITX5_ANDROID_NATIVESTREAMBUF_H
