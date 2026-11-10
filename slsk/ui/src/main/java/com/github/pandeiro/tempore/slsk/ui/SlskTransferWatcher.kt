package com.github.pandeiro.tempore.slsk.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import androidx.lifecycle.LifecycleOwner
import java.util.function.IntConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper

/**
 * 3 s foreground-only transfer poller backing the activity-screen badge and shared
 * state for the activity screen. All consumers reuse ONE ticker per resumed lifecycle
 * so two seams never double-poll slskd.
 */
object SlskTransferWatcher {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _activeCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Number of transfers queued or downloading from the latest observed snapshot. */
    val activeCount: kotlinx.coroutines.flow.StateFlow<Int> = _activeCount

    private class Ref(val owner: LifecycleOwner, val callback: IntConsumer, var job: Job)

    private val refs = mutableListOf<Ref>()

    /**
     * Observes active-transfers count while owner is at least resumed; tears down the
     * shared poller with the last unsubscription. Reports on the main thread.
     */
    fun observe(owner: LifecycleOwner, callback: IntConsumer) {
        val ref = Ref(owner, callback, SupervisorJob())
        synchronized(refs) { refs.add(ref) }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = Unit

            override fun onResume(owner: LifecycleOwner) = startPollingOwner(ref)

            override fun onPause(owner: LifecycleOwner) = stopPollingOwner(ref)

            override fun onDestroy(owner: LifecycleOwner) {
                stopPollingOwner(ref)
                synchronized(refs) { refs.remove(ref) }
            }
        })
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            startPollingOwner(ref)
        }
    }

    private fun startPollingOwner(ref: Ref) {
        if (ref.job.isActive) return
        val owner = ref.owner
        ref.job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try {
                    val groups = SlskSessionAccess.gatewayOrNull()?.transfers()?.first() ?: return@launch
                    val active = groups.sumOf { group ->
                        group.transfers.count {
                            it.lifecycle == com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle.QUEUED_REMOTE ||
                                it.lifecycle == com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle.QUEUED_LOCAL ||
                                it.lifecycle == com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle.DOWNLOADING
                        }
                    }
                    _activeCount.value = active
                    withContext(Dispatchers.Main) { ref.callback.accept(active) }
                } catch (_: Throwable) {
                    // unreachable slskd: report 0 but leave the banner to the screens
                    withContext(Dispatchers.Main) { ref.callback.accept(0) }
                }
                delay(3_000)
            }
        }
    }

    private fun stopPollingOwner(ref: Ref) {
        ref.job.cancel()
    }
}
