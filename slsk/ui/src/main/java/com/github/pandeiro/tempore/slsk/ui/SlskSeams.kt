package com.github.pandeiro.tempore.slsk.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.Navigation
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.function.IntConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Facade consumed by the upstream app (SearchFragment + MainActivity). Scope state lives
 * in [SlskPrefs]; the coordinator owns the one-active-search rule.
 */
object SlskSeams {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Cached so onSearchSubmitted never blocks on DataStore on the main thread. */
    @Volatile
    private var soulseekScopeActive = false

    /**
     * Injects the Library/Soulseek scope toggle as child 0 of parentAnchor.parent and
     * wires the search CTA card (id slsk_search_cta) when one exists in that container.
     * No-op when slskd is not configured: the library search must stay pure.
     */
    @JvmStatic
    fun attachScopeToggle(fragment: androidx.fragment.app.Fragment, parentAnchor: ViewGroup) {
        val container = parentAnchor.parent as? ViewGroup ?: return
        val context = parentAnchor.context
        if (!SlskSessionAccess.isConfigured) return

        val toggle = LayoutInflater.from(context)
            .inflate(R.layout.slsk_fragment_search_toggle, container, false) as MaterialButtonToggleGroup
        container.addView(toggle, 0)

        ioScope.launch {
            // Spec §5.2: Soulseek scope is never auto-selected — a persisted Soulseek
            // scope may not hijack the library search. Reset to Library on every
            // search-screen entry; the user's choice then lives for this session.
            if (soulseekScopeActive) {
                soulseekScopeActive = false
                SlskPrefs.setSlskScopeActive(context, false)
            }
            mainHandler.post {
                toggle.check(R.id.slsk_scope_button_library)
                updateCtaVisibility(container, false)
            }
        }

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val soulseek = checkedId == R.id.slsk_scope_button_soulseek
            soulseekScopeActive = soulseek
            ioScope.launch { SlskPrefs.setSlskScopeActive(context, soulseek) }
            updateCtaVisibility(container, soulseek)
        }

        // CTA card lives in the same container (S4 needs zero upstream Java).
        container.findViewById<View>(R.id.slsk_search_cta)?.let { cta ->
            cta.setOnClickListener {
                soulseekScopeActive = true
                ioScope.launch { SlskPrefs.setSlskScopeActive(context, true) }
                toggle.check(R.id.slsk_scope_button_soulseek)
                updateCtaVisibility(container, true)
            }
        }
    }

    private fun updateCtaVisibility(container: ViewGroup, slskActive: Boolean) {
        container.findViewById<View>(R.id.slsk_search_cta)?.visibility =
            if (slskActive) View.GONE else View.VISIBLE
    }

    /**
     * Top of SearchFragment.search(query). True when the Soulseek scope is active and the
     * slsk pipeline consumed the query (search started + results screen navigated).
     */
    @JvmStatic
    fun onSearchSubmitted(fragment: androidx.fragment.app.Fragment, query: String): Boolean {
        val context = fragment.context ?: return false
        if (!SlskSessionAccess.isConfigured || !soulseekScopeActive) return false
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val view = fragment.view ?: return false
        val run = SlskSearchCoordinator.start(trimmed)
        // The slsk graph is a nested <include>; deeplink navigation is the supported
        // way to reach destinations inside it (recorded in slsk/SEAMS.md, seam S6).
        val uri = android.net.Uri.parse("tempore://slsk/results/" + java.net.URLEncoder.encode(run.searchId, "UTF-8"))
        Navigation.findNavController(view).navigate(uri)
        return true
    }

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
