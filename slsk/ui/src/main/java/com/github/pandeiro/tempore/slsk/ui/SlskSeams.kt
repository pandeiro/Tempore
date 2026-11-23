package com.github.pandeiro.tempore.slsk.ui

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.Navigation
import com.github.pandeiro.tempore.slsk.core.store.SlskSearchHistoryEntry
import com.github.pandeiro.tempore.slsk.core.store.SlskSearchScope
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import java.util.WeakHashMap
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Facade consumed by the upstream app (SearchFragment, seam S3). The search screen itself is
 * [SlskSearchScreen]; the coordinator owns the one-active-search rule.
 */
object SlskSeams {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One controller per live SearchFragment view (weak: fragments come and go). */
    private val screens = WeakHashMap<Fragment, SlskSearchScreen>()

    @Volatile
    private var refineRequested = false

    /**
     * Installs the fork's search screen into upstream's SearchView (PLAN.md §8). Always
     * installed — the Library fixes apply without slskd; Soulseek pieces (toggle, Downloads,
     * last results, CTA) appear only once slskd is configured.
     */
    @JvmStatic
    fun attachSearchScreen(
        fragment: Fragment,
        searchBar: SearchBar,
        searchView: SearchView,
        suggestionContainer: ViewGroup,
        emptyResponseContainer: ViewGroup,
        submit: Consumer<String>,
    ) {
        val screen = SlskSearchScreen(fragment, searchBar, searchView, suggestionContainer, emptyResponseContainer, submit)
        synchronized(screens) { screens[fragment] = screen }
        screen.attach()
    }

    /** Guards upstream's `inputFocus()`: auto-expand (with keyboard) on a fresh entry only. */
    @JvmStatic
    fun shouldAutoShow(fragment: Fragment): Boolean = screen(fragment)?.freshEntry ?: true

    /** Upstream's recents/suggestion rendering stays off: [SlskSearchScreen] owns that container. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun ownsSuggestions(fragment: Fragment): Boolean = true

    /**
     * Top of SearchFragment.search(query). True when the Soulseek scope is active and the
     * slsk pipeline consumed the query (search started + results screen navigated). Library
     * submits are recorded in the Library history and fall through (false).
     */
    @JvmStatic
    fun onSearchSubmitted(fragment: Fragment, query: String): Boolean {
        val trimmed = query.trim()
        val screen = screen(fragment)
        if (screen == null || screen.scope != SlskSearchScope.SOULSEEK) {
            if (trimmed.isNotEmpty()) screen?.onLibrarySubmitted(trimmed)
            return false
        }
        if (trimmed.isEmpty()) return true
        val view = fragment.view ?: return false
        // Upstream's editor-action listener fires for both ENTER down and up (hardware/adb
        // keys): a second submit lands while results are already current. Consuming it here
        // keeps exactly one results screen (and one run) per search.
        val current = Navigation.findNavController(view).currentDestination?.id ?: return false
        if (current == R.id.slskResultsFragment) return true
        screen.onSoulseekSubmitted(trimmed)
        val run = SlskSearchCoordinator.start(trimmed)
        openResults(fragment, run.searchId, trimmed)
        return true
    }

    /**
     * Opens (or reopens) a Soulseek results screen by search id. Deep link into the nested
     * <include> graph (decision D4: NavController resolves ids only among the current graph's
     * direct children + parents, never inside child graphs). popUpTo the calling search
     * destination: exactly one results screen in the stack, back from results always lands
     * on Search (recorded in slsk/SEAMS.md, seam S6).
     */
    internal fun openResults(fragment: Fragment, searchId: String, query: String) {
        val view = fragment.view ?: return
        val controller = Navigation.findNavController(view)
        val searchDestination = controller.currentDestination?.id ?: return
        if (searchDestination == R.id.slskResultsFragment) return
        val uri = android.net.Uri.parse("tempore://slsk/results").buildUpon()
            .appendPath(searchId)
            .appendQueryParameter("q", query) // reopen/re-run target after process death
            .build()
        val options = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(searchDestination, false)
            .build()
        controller.navigate(uri, options)
    }

    /** Soulseek activity (Downloads) from the search screen, by deep link (D4). */
    internal fun openDownloads(fragment: Fragment) {
        val view = fragment.view ?: return
        val controller = Navigation.findNavController(view)
        if (controller.currentDestination?.id == R.id.slskActivityFragment) return
        controller.navigate(android.net.Uri.parse("tempore://slsk/activity"))
    }

    /** Results screen → "Edit search": Search reopens on Soulseek with the query to refine. */
    internal fun requestRefine() {
        refineRequested = true
    }

    internal fun consumeRefineRequest(): Boolean {
        val requested = refineRequested
        refineRequested = false
        return requested
    }

    internal fun recordLibrarySearch(query: String) {
        ioScope.launch { runCatching { SlskSessionAccess.stores.addSearch(query, SlskSearchScope.LIBRARY) } }
    }

    internal fun removeRecent(entry: SlskSearchHistoryEntry) {
        ioScope.launch { runCatching { SlskSessionAccess.stores.removeSearch(entry.query, entry.scope) } }
    }

    internal fun detach(fragment: Fragment, screen: SlskSearchScreen) {
        synchronized(screens) { if (screens[fragment] === screen) screens.remove(fragment) }
    }

    private fun screen(fragment: Fragment): SlskSearchScreen? = synchronized(screens) { screens[fragment] }

    /**
     * Toolbar badge seam (§5.1: activity icon shows the active transfer count).
     * Polls the 3 s cadence only while `owner` is resumed and reports the count of
     * queued-or-downloading transfers on the main thread.
     */
    @JvmStatic
    fun observeTransferCount(owner: LifecycleOwner, callback: IntConsumer) {
        SlskTransferWatcher.observe(owner, callback)
    }
}
