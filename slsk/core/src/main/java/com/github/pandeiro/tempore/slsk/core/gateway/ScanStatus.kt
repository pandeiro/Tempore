package com.github.pandeiro.tempore.slsk.core.gateway

/**
 * Bounded, tiny contract for what the handoff needs from a scan. Timestamps as epoch
 * millis (upstream has no java.time desugaring and slskd returns millis too).
 */
sealed interface ScanStatus {
    /** slskd/Subsonic agreed to start or is running a scan. */
    data class InProgress(val startedAt: Long?) : ScanStatus

    data class Completed(val timeStamp: Long? = null) : ScanStatus

    data class Unknown(val raw: String? = null) : ScanStatus
}
