package com.github.pandeiro.tempore.slsk.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Tiny event bus between screens that cannot reach each other's fragment views
 * (dialog sheets → host screens). Payloads are ids/strings only (spec §7).
 */
object SlskUiBus {
    class EnqueueEvent(val directory: String, val queuedFiles: Int, val failures: List<String>)

    private val _enqueue = MutableSharedFlow<EnqueueEvent>(extraBufferCapacity = 8)
    val enqueue: SharedFlow<EnqueueEvent> = _enqueue

    fun notifyEnqueue(path: String, queued: Int, failures: List<String>) {
        _enqueue.tryEmit(EnqueueEvent(path, queued, failures))
    }
}
