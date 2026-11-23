package com.github.pandeiro.tempore.glue

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.navigation.fragment.NavHostFragment
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Preferences
import com.github.pandeiro.tempore.slsk.core.gateway.LibraryAlbumMatch
import com.github.pandeiro.tempore.slsk.core.gateway.NavidromeConfig
import java.lang.ref.WeakReference

/**
 * One-way bridge from :slsk:core into the Tempus host app. This is the only slsk
 * source allowed to import `com.eddyizm.tempus.**`.
 *
 * Credential mapping per slsk/SPIKE.md: token auth (token/salt) when a token and salt
 * are stored and "low security" mode is off; plain password otherwise.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TemporeHostBridge : com.github.pandeiro.tempore.slsk.core.gateway.HostBridge, Application.ActivityLifecycleCallbacks {

    private var activityRef: WeakReference<MainActivity>? = null

    override fun navidrome(): NavidromeConfig? {
        val url = Preferences.getInUseServerAddress()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return null
        val user = Preferences.getUser()?.takeIf { it.isNotBlank() } ?: return null
        val token = Preferences.getToken()
        val salt = Preferences.getSalt()
        val useTokenAuth = !Preferences.isLowScurity() && !token.isNullOrBlank() && !salt.isNullOrBlank()
        return NavidromeConfig(
            serverUrl = url.removeSuffix("/"),
            username = user,
            useTokenAuth = useTokenAuth,
            password = if (useTokenAuth) null else Preferences.getPassword(),
            token = if (useTokenAuth) token else null,
            salt = if (useTokenAuth) salt else null,
            musicFolderId = Preferences.getActiveMusicFolderId()?.trim()?.toIntOrNull(),
        )
    }

    override fun openAlbum(albumId: String, albumName: String, artistName: String) {
        val activity = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        // Mirror SearchFragment.onAlbumClick / AlbumAdapter: parcelable under Constants.ALBUM_OBJECT.
        val album = com.eddyizm.tempus.subsonic.models.AlbumID3().apply {
            id = albumId
            name = albumName
            artist = artistName
        }
        val bundle = Bundle().apply {
            putParcelable(com.eddyizm.tempus.util.Constants.ALBUM_OBJECT, album.strippedForNav())
        }
        val navHost = activity.supportFragmentManager
            .findFragmentById(com.eddyizm.tempus.R.id.nav_host_fragment) as? NavHostFragment
        // Same raw-id navigation as SearchFragment.onAlbumClick.
        (navHost?.navController
            ?: androidx.navigation.Navigation.findNavController(activity, com.eddyizm.tempus.R.id.nav_host_fragment))
            .navigate(com.eddyizm.tempus.R.id.albumPageFragment, bundle)
    }

    /**
     * Same request upstream's SearchingRepository.getSuggestions makes (search3, 5 artists /
     * 5 albums / 5 songs), synchronously on IO; the search screen sanitizes the names.
     */
    override suspend fun librarySuggestions(query: String): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val response = com.eddyizm.tempus.App.getSubsonicClientInstance(false)
                .searchingClient
                .search3(query, 5, 0, 5, 0, 5, 0)
                .execute()
            val result = response.body()?.subsonicResponse?.searchResult3
                ?: return@withContext emptyList()
            buildList {
                result.artists?.forEach { add(it.name) }
                result.albums?.forEach { add(it.name) }
                result.songs?.forEach { add(it.title) }
            }.filterNotNull()
        }

    /** Convenience for the core handoff: convert a LibraryAlbumMatch into an open navigation. */
    fun openMatch(match: LibraryAlbumMatch) {
        openAlbum(match.id, match.name, match.artist)
    }

    // ---- Application.ActivityLifecycleCallbacks: track the foreground MainActivity ----

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) activityRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }
}
