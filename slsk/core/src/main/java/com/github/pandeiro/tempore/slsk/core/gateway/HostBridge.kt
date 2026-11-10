package com.github.pandeiro.tempore.slsk.core.gateway

import com.github.pandeiro.tempore.slsk.core.gateway.ScanStatus

interface LibraryGateway {
    /** search3 for an album by artist/title; null means "not visible in library yet". */
    suspend fun findAlbum(artist: String, album: String): LibraryAlbumMatch?

    suspend fun startScan(): Boolean

    suspend fun scanStatus(): ScanStatus?
}

/** Minimal album result the handoff needs to deep-link and confirm visibility. */
data class LibraryAlbumMatch(
    val id: String,
    val name: String,
    val artist: String,
    val songCount: Int?,
)

/** Navidrome/Subsonic server material fed in by the HostBridge; never re-prompted in the slsk UI. */
data class NavidromeConfig(
    val serverUrl: String,
    val username: String,
    val useTokenAuth: Boolean,
    val password: String?,   // when useTokenAuth == false
    val token: String?,      // md5(password+salt)
    val salt: String?,
    val musicFolderId: Int?,
)

/** One-way bridge implemented in slsk/app-glue (only code allowed to import upstream classes). */
interface HostBridge {
    /** Returning null means the app is not signed into a Subsonic server; handoff becomes manual-only. */
    fun navidrome(): NavidromeConfig?

    /** Navigate to the given album inside the host app (deep-link handoff). */
    fun openAlbum(albumId: String, albumName: String, artistName: String)
}
