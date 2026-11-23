package com.github.pandeiro.tempore.slsk.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.pandeiro.tempore.slsk.core.domain.SuggestionSanitizer
import com.github.pandeiro.tempore.slsk.core.store.SlskSearchHistoryEntry
import com.github.pandeiro.tempore.slsk.core.store.SlskSearchScope
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search-screen state that must survive the back stack (the fragment's view is recreated
 * when you come back from results/album/activity) but never a fresh entry into Search:
 * a ViewModel scoped to the SearchFragment instance. Ids and strings only.
 */
class SlskSearchScreenState : ViewModel() {
    /** False until the first view of this fragment instance attached (fresh entry). */
    var entered = false
    var scope = SlskSearchScope.LIBRARY

    /** What the user left in the input, per scope; restored on every expand (I3). */
    val drafts = mutableMapOf(SlskSearchScope.LIBRARY to "", SlskSearchScope.SOULSEEK to "")

    /** Query of the Library results shown under the bar (drives bar text + CTA). */
    var lastLibraryQuery: String? = null
    var lastSubmitScope: SlskSearchScope? = null
    var showAllRecents = false
}

/**
 * Owns the inside of upstream's expanded SearchView (PLAN.md §8): scope toggle + Downloads in
 * the header, per-scope hints/IME flags, scoped recents (newest first), sanitized Library
 * candidates, the Soulseek "last results" card, draft restore, and the entry policy.
 * One instance per SearchFragment view; upstream's own suggestion rendering is switched off
 * through [SlskSeams.ownsSuggestions].
 */
internal class SlskSearchScreen(
    private val fragment: Fragment,
    private val searchBar: SearchBar,
    private val searchView: SearchView,
    private val container: ViewGroup,
    private val emptyResponse: ViewGroup,
    private val submit: Consumer<String>,
) {
    private val state: SlskSearchScreenState = ViewModelProvider(fragment)[SlskSearchScreenState::class.java]
    private val context = searchView.context
    private val owner: LifecycleOwner = fragment.viewLifecycleOwner
    private val configured = SlskSessionAccess.isConfigured
    private val editText = searchView.editText
    private val libraryViewHint: CharSequence? = editText.hint
    private val libraryBarHint: CharSequence? = searchBar.hint
    private val libraryInputType = editText.inputType

    /** First view of this fragment instance: upstream's auto-expand (with keyboard) applies. */
    val freshEntry: Boolean = !state.entered
    private val refine = SlskSeams.consumeRefineRequest() && !freshEntry

    /** Set while WE (or Material's show animation) write the input, so drafts stay the user's. */
    private var restoring = false
    private var programmaticToggle = false
    private var toggle: MaterialButtonToggleGroup? = null
    private var downloadsButton: MaterialButton? = null
    private var badge: BadgeDrawable? = null
    private var candidatesJob: Job? = null
    private var liveJob: Job? = null
    private var libraryHistory = emptyList<SlskSearchHistoryEntry>()
    private var soulseekHistory = emptyList<SlskSearchHistoryEntry>()

    /** Effective scope: Soulseek is unreachable while slskd is not configured. */
    val scope: SlskSearchScope get() = if (configured) state.scope else SlskSearchScope.LIBRARY

    fun attach() {
        state.entered = true
        if (!configured) state.scope = SlskSearchScope.LIBRARY
        if (configured) {
            installHeader()
            installBarMenu()
        }
        installCta()
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onInputChanged(s?.toString().orEmpty())
        })
        searchView.addTransitionListener { _, _, newState -> onTransition(newState) }
        applyScope()
        collectHistory()
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                // After view-state restore (which re-applies the saved SearchBar/SearchView text).
                if (!freshEntry) onReturn()
            }
        })
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                candidatesJob?.cancel()
                liveJob?.cancel()
                SlskSeams.detach(fragment, this@SlskSearchScreen)
            }
        })
    }

    // ------------------------------------------------------------------ entry policy

    /**
     * Back-stack return: never re-expand over results you came back to (I7). Library results
     * are re-run (the recreated view has empty adapters); otherwise the SearchView opens on
     * recents / last results WITHOUT the keyboard. A refine request opens Soulseek to edit.
     */
    private fun onReturn() {
        applyScope()
        val libraryQuery = state.lastLibraryQuery
        when {
            refine -> {
                state.scope = SlskSearchScope.SOULSEEK
                applyScope()
                searchView.show()
            }
            state.lastSubmitScope == SlskSearchScope.LIBRARY && libraryQuery != null -> {
                if (state.scope != SlskSearchScope.LIBRARY) {
                    state.scope = SlskSearchScope.LIBRARY
                    applyScope()
                }
                submit.accept(libraryQuery)
            }
            else -> showWithoutKeyboard()
        }
    }

    private fun showWithoutKeyboard() {
        // Already expanded (restored view state, e.g. rotation): no SHOWN transition would come
        // to re-enable the keyboard for later user-initiated expands.
        if (searchView.isShowing) {
            render()
            return
        }
        searchView.setAutoShowKeyboard(false)
        searchView.show()
    }

    // ------------------------------------------------------------------ submit hooks

    fun onLibrarySubmitted(query: String) {
        state.lastLibraryQuery = query
        state.lastSubmitScope = SlskSearchScope.LIBRARY
        state.drafts[SlskSearchScope.LIBRARY] = query
        SlskSeams.recordLibrarySearch(query)
        updateCta()
    }

    fun onSoulseekSubmitted(query: String) {
        state.lastSubmitScope = SlskSearchScope.SOULSEEK
        state.drafts[SlskSearchScope.SOULSEEK] = query
        collapseNow()
    }

    /**
     * Hides the SearchView immediately (no animation) before navigating away: the saved view
     * state then says "hidden", and navigation never detaches it mid-hide (seam R1 notes).
     */
    fun collapseNow() {
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(editText.windowToken, 0)
        if (searchView.isShowing) searchView.setVisible(false)
    }

    // ------------------------------------------------------------------ input + drafts

    private fun onTransition(newState: SearchView.TransitionState) {
        when (newState) {
            // Material copies SearchBar.getText() into the input right after entering SHOWING
            // (the source of the query ghost, I3). Our post runs before the content is shown.
            SearchView.TransitionState.SHOWING -> {
                restoring = true
                searchView.post { restoreDraft() }
            }
            SearchView.TransitionState.SHOWN -> {
                if (restoring) restoreDraft()
                searchView.setAutoShowKeyboard(true)
            }
            SearchView.TransitionState.HIDDEN -> {
                candidatesJob?.cancel()
                liveJob?.cancel()
            }
            else -> Unit
        }
    }

    private fun restoreDraft() {
        restoring = false
        setInput(state.drafts[scope].orEmpty())
        render()
    }

    private fun setInput(text: String) {
        restoring = true
        if (editText.text?.toString() != text) {
            editText.setText(text)
            editText.setSelection(text.length)
        }
        restoring = false
    }

    private fun onInputChanged(text: String) {
        if (restoring) return
        state.drafts[scope] = text
        render()
    }

    // ------------------------------------------------------------------ scope

    private fun installHeader() {
        val header = LayoutInflater.from(context).inflate(R.layout.slsk_view_search_header, searchView, false)
        searchView.addHeaderView(header)
        toggle = header.findViewById<MaterialButtonToggleGroup>(R.id.slsk_scope_toggle).also { group ->
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || programmaticToggle) return@addOnButtonCheckedListener
                switchScope(
                    if (checkedId == R.id.slsk_scope_button_soulseek) SlskSearchScope.SOULSEEK
                    else SlskSearchScope.LIBRARY
                )
            }
        }
        downloadsButton = header.findViewById<MaterialButton>(R.id.slsk_search_downloads).also { button ->
            button.setOnClickListener { openDownloads() }
        }
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalBadgeUtils::class])
    private fun installBarMenu() {
        searchBar.inflateMenu(R.menu.slsk_menu_search)
        searchBar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.slsk_action_search_downloads) {
                openDownloads()
                true
            } else {
                false
            }
        }
        badge = BadgeDrawable.create(context).also { drawable ->
            drawable.isVisible = false
            runCatching { BadgeUtils.attachBadgeDrawable(drawable, searchBar, R.id.slsk_action_search_downloads) }
        }
        SlskTransferWatcher.observe(owner) { count -> showTransferCount(count) }
    }

    private fun showTransferCount(count: Int) {
        if (fragment.view == null) return
        downloadsButton?.text = if (count > 0) count.toString() else ""
        badge?.let {
            it.number = count
            it.isVisible = count > 0
        }
    }

    private fun switchScope(next: SlskSearchScope) {
        if (next == state.scope) return
        state.scope = next
        state.showAllRecents = false
        applyScope()
        setInput(state.drafts[next].orEmpty())
        render()
    }

    /** Toggle, hints, IME flags, bar text and CTA follow the scope. Never submits. */
    private fun applyScope() {
        val soulseek = scope == SlskSearchScope.SOULSEEK
        toggle?.let { group ->
            programmaticToggle = true
            group.check(if (soulseek) R.id.slsk_scope_button_soulseek else R.id.slsk_scope_button_library)
            programmaticToggle = false
        }
        val soulseekHint = context.getString(R.string.slsk_search_hint_soulseek)
        searchView.setHint(if (soulseek) soulseekHint else libraryViewHint)
        searchBar.setHint(if (soulseek) soulseekHint else libraryBarHint)
        // Soulseek queries are deliberate partial terms: no autocorrect/suggestion strip (I4).
        val inputType = if (soulseek) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            libraryInputType
        }
        if (editText.inputType != inputType) {
            editText.inputType = inputType
            context.getSystemService(InputMethodManager::class.java)?.restartInput(editText)
        }
        // The bar only ever shows the query of the Library results beneath it.
        searchBar.setText(if (soulseek) null else state.lastLibraryQuery)
        updateCta()
    }

    // ------------------------------------------------------------------ CTA

    private fun installCta() {
        emptyResponse.findViewById<View>(R.id.slsk_search_cta)?.setOnClickListener {
            val query = state.lastLibraryQuery
            state.scope = SlskSearchScope.SOULSEEK
            applyScope()
            if (query.isNullOrBlank()) {
                searchView.show()
            } else {
                state.drafts[SlskSearchScope.SOULSEEK] = query
                submit.accept(query)
            }
        }
    }

    private fun updateCta() {
        val cta = emptyResponse.findViewById<View>(R.id.slsk_search_cta) ?: return
        cta.visibility = if (configured && scope == SlskSearchScope.LIBRARY) View.VISIBLE else View.GONE
        val query = state.lastLibraryQuery
        cta.findViewById<TextView>(R.id.slsk_search_cta_title)?.text =
            if (query.isNullOrBlank()) context.getString(R.string.slsk_search_cta_title)
            else context.getString(R.string.slsk_search_cta_title_query, query)
    }

    // ------------------------------------------------------------------ history + render

    private fun collectHistory() {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val stores = runCatching { SlskSessionAccess.stores }.getOrNull() ?: return@repeatOnLifecycle
                combine(
                    stores.searchHistory(SlskSearchScope.LIBRARY),
                    stores.searchHistory(SlskSearchScope.SOULSEEK),
                ) { library, soulseek -> library to soulseek }
                    .collect { (library, soulseek) ->
                        libraryHistory = library
                        soulseekHistory = soulseek
                        if (showingHistory()) render()
                    }
            }
        }
    }

    private fun currentInput(): String = editText.text?.toString()?.trim().orEmpty()

    private fun showingHistory(): Boolean =
        scope == SlskSearchScope.SOULSEEK || currentInput().length < MIN_CANDIDATE_CHARS

    private fun render() {
        if (fragment.view == null) return
        val text = currentInput()
        liveJob?.cancel()
        if (scope == SlskSearchScope.LIBRARY && text.length >= MIN_CANDIDATE_CHARS) {
            loadCandidates(text)
            return
        }
        candidatesJob?.cancel()
        container.removeAllViews()
        when (scope) {
            SlskSearchScope.LIBRARY -> renderRecents(libraryHistory, R.string.slsk_search_recent_library)
            // Ad hoc by design (I4): nothing is suggested while a Soulseek query is typed.
            SlskSearchScope.SOULSEEK -> if (text.isEmpty()) renderSoulseekHome()
        }
    }

    private fun renderSoulseekHome() {
        val latest = soulseekHistory.firstOrNull() ?: return
        addResumeCard(latest)
        renderRecents(soulseekHistory.drop(1), R.string.slsk_search_recent_soulseek)
    }

    private fun renderRecents(entries: List<SlskSearchHistoryEntry>, @StringRes title: Int) {
        if (entries.isEmpty()) return
        addSection(context.getString(title))
        val shown = if (state.showAllRecents) entries else entries.take(RECENT_LIMIT)
        for (entry in shown) {
            addRow(
                icon = R.drawable.slsk_ic_history,
                title = entry.query,
                subtitle = if (entry.scope == SlskSearchScope.SOULSEEK) status(entry) else null,
                onClick = { openRecent(entry) },
                onRemove = { SlskSeams.removeRecent(entry) },
            )
        }
        if (entries.size > RECENT_LIMIT) {
            val label = if (state.showAllRecents) context.getString(R.string.slsk_search_show_fewer)
            else context.getString(R.string.slsk_search_show_all, entries.size)
            addSection(label).apply {
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                setOnClickListener {
                    state.showAllRecents = !state.showAllRecents
                    render()
                }
            }
        }
    }

    private fun loadCandidates(query: String) {
        candidatesJob?.cancel()
        val host = SlskSessionAccess.host
        if (host == null) {
            container.removeAllViews()
            return
        }
        // Keep the previous rows until the new answer arrives (no flicker while typing).
        candidatesJob = owner.lifecycleScope.launch {
            delay(CANDIDATE_DEBOUNCE_MS)
            val raw = withContext(Dispatchers.IO) {
                runCatching { host.librarySuggestions(query) }.getOrDefault(emptyList())
            }
            val candidates = SuggestionSanitizer.sanitize(raw)
            if (currentInput() != query || scope != SlskSearchScope.LIBRARY || fragment.view == null) return@launch
            container.removeAllViews()
            for (candidate in candidates) {
                addRow(R.drawable.slsk_ic_search, candidate, null, onClick = { submit.accept(candidate) }, onRemove = null)
            }
        }
    }

    private fun openRecent(entry: SlskSearchHistoryEntry) {
        when (entry.scope) {
            SlskSearchScope.LIBRARY -> submit.accept(entry.query)
            SlskSearchScope.SOULSEEK -> {
                val searchId = entry.searchId
                if (searchId == null) {
                    submit.accept(entry.query)
                } else {
                    // Reopen, never re-run: memory first, then slskd (I1).
                    state.lastSubmitScope = SlskSearchScope.SOULSEEK
                    state.drafts[SlskSearchScope.SOULSEEK] = entry.query
                    collapseNow()
                    SlskSeams.openResults(fragment, searchId, entry.query)
                }
            }
        }
    }

    private fun openDownloads() {
        state.lastSubmitScope = state.lastSubmitScope ?: scope
        collapseNow()
        SlskSeams.openDownloads(fragment)
    }

    /** "Searching · 12 folders", "41 folders · 2 hours ago", or just the age. */
    private fun status(entry: SlskSearchHistoryEntry): String {
        val run = entry.searchId?.let { SlskSearchCoordinator.find(it) }
        val live = run?.isLive == true
        val folders = run?.latest?.groups?.size ?: entry.folderCount ?: if (live) 0 else null
        val folderText = folders?.let { context.resources.getQuantityString(R.plurals.slsk_search_folders, it, it) }
        val age = DateUtils.getRelativeTimeSpanString(
            entry.atEpochMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        return when {
            live && folderText != null -> context.getString(R.string.slsk_search_status_searching, folderText)
            folderText != null -> context.getString(R.string.slsk_search_status_joiner, folderText, age)
            else -> age
        }
    }

    // ------------------------------------------------------------------ views

    private fun addSection(label: String): TextView {
        val view = LayoutInflater.from(context).inflate(R.layout.slsk_item_search_section, container, false) as TextView
        view.text = label
        container.addView(view)
        return view
    }

    private fun addResumeCard(entry: SlskSearchHistoryEntry) {
        val card = LayoutInflater.from(context).inflate(R.layout.slsk_item_search_resume, container, false)
        card.findViewById<TextView>(R.id.slsk_resume_title).text =
            context.getString(R.string.slsk_search_resume_title, entry.query)
        val statusView = card.findViewById<TextView>(R.id.slsk_resume_status)
        statusView.text = status(entry)
        card.setOnClickListener { openRecent(entry) }
        container.addView(card)
        val run = entry.searchId?.let { SlskSearchCoordinator.find(it) }
        if (run != null && run.isLive) {
            liveJob = owner.lifecycleScope.launch {
                run.progress.collect { statusView.text = status(entry) }
            }
        }
    }

    private fun addRow(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String?,
        onClick: () -> Unit,
        onRemove: (() -> Unit)?,
    ) {
        val row = LayoutInflater.from(context).inflate(R.layout.slsk_item_search_row, container, false)
        row.findViewById<ImageView>(R.id.slsk_row_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.slsk_row_title).text = title
        row.findViewById<TextView>(R.id.slsk_row_subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.slsk_row_remove).apply {
            visibility = if (onRemove == null) View.GONE else View.VISIBLE
            setOnClickListener { onRemove?.invoke() }
        }
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    private companion object {
        const val RECENT_LIMIT = 6
        const val MIN_CANDIDATE_CHARS = 2
        const val CANDIDATE_DEBOUNCE_MS = 200L
    }
}
