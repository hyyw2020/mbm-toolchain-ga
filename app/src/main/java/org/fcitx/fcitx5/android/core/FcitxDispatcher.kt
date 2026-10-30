/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class FcitxDispatcher(private val controller: FcitxController) : CoroutineDispatcher() {

    class WrappedRunnable(private val runnable: Runnable) : Runnable by runnable {
        private val time = System.currentTimeMillis()

        override fun run() {
            val delta = System.currentTimeMillis() - time
            if (delta > JOB_WAITING_LIMIT) {
                Timber.w("$this has waited $delta ms to get run since created!")
            }
            runnable.run()
        }

        override fun toString(): String = "WrappedRunnable[${hashCode()}]"
    }

    // this is fcitx main thread
    private val fcitxThread = AtomicReference<Thread?>(null)
    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it).apply {
            name = "fcitx-main"
            fcitxThread.set(this)
        }
    }.asCoroutineDispatcher()

    /** 当前线程是否就是 fcitx 单线程（用于检测 runImmediately 的重复进入，避免自锁）。 */
    fun isFcitxThread(): Boolean = Thread.currentThread() == fcitxThread.get()

    private val internalScope = CoroutineScope(internalDispatcher)

    interface FcitxController {
        fun nativeStartup()
        fun nativeLoopOnce()
        fun nativeScheduleEmpty()
        fun nativeExit()
    }

    private val runningLock = Mutex()

    private val queue = ConcurrentLinkedQueue<WrappedRunnable>()

    private val isRunning = AtomicBoolean(false)

    /**
     * Start the dispatcher
     * This function returns immediately
     */
    fun start() {
        Timber.d("FcitxDispatcher start()")
        internalScope.launch {
            runningLock.withLock {
                if (isRunning.compareAndSet(false, true)) {
                    Timber.d("nativeStartup()")
                    controller.nativeStartup()
                    while (isActive && isRunning.get()) {
                        // blocking...
                        controller.nativeLoopOnce()
                        // do scheduled jobs
                        while (true) {
                            val block = queue.poll() ?: break
                            block.run()
                        }
                    }
                    Timber.i("nativeExit()")
                    controller.nativeExit()
                }
            }
        }
    }

    /**
     * Stop the dispatcher
     * This function blocks until fully stopped
     */
    fun stop(): List<Runnable> {
        Timber.i("FcitxDispatcher stop()")
        return if (isRunning.compareAndSet(true, false)) {
            runBlocking {
                controller.nativeScheduleEmpty()
                runningLock.withLock {
                    val rest = queue.toList()
                    queue.clear()
                    rest
                }
            }
        } else emptyList()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!isRunning.get()) {
            throw IllegalStateException("Dispatcher is not in running state!")
        }
        queue.offer(WrappedRunnable(block))
        // always call `nativeScheduleEmpty()` to prevent `nativeLoopOnce()` from blocking
        // the thread when we have something to run
        controller.nativeScheduleEmpty()
    }

    companion object {
        const val JOB_WAITING_LIMIT = 2000L
    }

}