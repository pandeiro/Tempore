### SlskSessionAccess expectations (kept current with CoreImpl)
- `SlskSession.currentConfig: SlskdConfig?` — implemented by CoreImpl (saveConfig updates gateway + currentConfig atomically).
- UI submits trimmed URL; appends `http://` when the user typed a bare host:port (core does the trailing slash).

### UI-layer open questions / deviations
1. Transfer queue position: `Transfer` has no numeric position field; queued rows show the lifecycle label + ETA from `remainingTimeSeconds`. If core later adds `position`, thread it into `SlskActivityGroupAdapter.stateLabel`.
2. First-use sheet shows once per install (DataStore `slsk_ui_prefs`), not once per account — no account concept in the MVP.
3. Settings "Test connection" saves the typed config, probes with the rebuilt gateway, then restores the previous config so a half-verified URL never becomes the active gateway.
4. Extra seam beyond the two locked SearchFragment ones: `SlskSeams.observeTransferCount(lifecycleOwner, IntConsumer)` for the MainActivity activity-icon badge (spec §5.1) — Main wires it.
5. Retry after process death: ids-only policy means the query is recoverable only from the in-memory LRU; otherwise the results screen shows the search-failed banner honestly instead of faking stale data.
6. "Sort: preferred format" menu item is informational — sorted by core's `RankingEngine` (16/44-preferred order); the UI delegates instead of duplicating the comparator.
7. `SlskActivityFragment` is navigable both as nav destination and embedded in `SlskSettingsActivity` (spec: "activity screen also reachable from Settings"); embedded mode falls back to fragment removal on back.
8. Filters are per-run in memory (spec says "filter choices persist" — MVP keeps them per run; if persisted is required hoist `SlskResultFilters` into `SlskPrefs`).

Status: `:slsk:ui:assembleDebug` and `:app:compileTempusDebugKotlin` both GREEN (Nov 8, 2026).
