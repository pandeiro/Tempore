# PLAN.md — Tempore living spec & development log

**This is the canonical feature/behavior spec for the Tempore fork.** It merges the
work-in-progress drafts (claude architecture draft + qwen draft, both from 2026-11-07, kept
in git history only). Claude's architecture and scope won; qwen's Compose/Room/Hilt choices
were rejected to keep the upstream merge delta tiny. The merged background reference lives in
§5 below. Rule 1 applies from here on: changes go to the log with the code.

Rules for this file:

1. Every feature/behavior change lands here **before or with the code**: add a dated entry to
   the log below, and update the relevant section of this document so it always describes
   current reality.
2. Specs, not wishes: each feature states its scope, decisions, and how it is verified.
3. When upstream behavior is knowingly superseded (e.g. a bug fix in fork code), the entry
   notes it — those lines are upstream-sync attention points (see also `slsk/SEAMS.md`).

---

## 1. Product definition

Tempus (Subsonic/Navidrome Android client), rebranded **Tempore** and extended with
Soulseek search & download backed by a self-hosted slskd; Navidrome is the library of record.
Design principles (unchanged from the claude draft §1): slskd is the source of truth; the app
is a thin client; additive modules + tiny seams upstream; all slskd I/O behind a gateway
interface; Library search and Soulseek search are never blended.

## 2. Current feature set (nailed down)

### 2.1 Identity / rebrand
- applicationId `com.github.pandeiro.tempore` (flavors `.degoogled`, `.play` debug builds
  add `.debug`); Kotlin package stays `com.eddyizm.tempus`.
- Launcher label "Tempore", same icon recolored sandstone orange (`#FFC98352`); in-app
  toolbar title "Tempore" (default `app_name` only; per-locale copies untouched). Every
  remaining tempus-red launcher asset (`ic_launcher_background/foreground`) and the
  home-screen long-press "Log in" shortcut icon (`@mipmap/slsk_launcher`) are sandstone too.
- Release signing when `slsk/keystore/local.properties` exists (gitignored).

### 2.2 slskd settings & connection
- Settings → **Soulseek (slskd)** category (accordion row, wired like upstream's LAN entry).
- Fields: base URL (scheme auto-prepended for bare `host:port`), API key (masked, blank =
  keep saved), optional cert SHA-256 pin (TOFU, scope-limited to the slskd client). Each
  field has one floating label; example/explanation text is a Material placeholder shown
  only while the field is empty.
- **Test connection** shows reachable · authenticated · slskd version · Soulseek state.
- Auto-scan toggle (on by default) after the download handoff.
- Credentials: Keystore AES-GCM over one JSON blob in `slsk_secure` prefs; API key never in
  URL, redacted (`redactHeader`) from any HTTP log; no security-crypto, no Room.
- Debug builds only: `SlskSettingsActivity` accepts `slsk_debug_url` / `slsk_debug_key`
  extras to seed config (used by adb-driven acceptance runs).

### 2.3 Search scope & results
- **Search screen** (fork controller `SlskSearchScreen`, seam S3; details and rationale in
  §8) owns the inside of upstream's expanded SearchView. It installs even without slskd;
  the Soulseek pieces appear only once slskd is configured.
  - The **Library/Soulseek toggle and a Downloads button** (active-transfer count) sit in
    the SearchView header (`addHeaderView`), so they stay usable while typing. The
    collapsed SearchBar also gets a Downloads menu icon with a badge.
  - Switching scope keeps each scope's own draft, swaps the hint ("Search Soulseek (any
    words)"), and turns IME suggestions off for Soulseek. It never submits.
  - **Scope policy (D5 as amended)**: a fresh entry into Search starts on Library. A
    back-stack return keeps the scope in use. The scope is never persisted.
  - **Library**: with empty input, show the 6 most recent Library searches, newest first,
    with "Show all" and per-row remove. From 2 characters, show live candidates (upstream's
    search3 request via the host bridge), sanitized: trimmed, ≤ 60 chars, deduped
    case-insensitively, at most 10.
  - **Soulseek**: ad hoc, with no autocomplete. With empty input, a "Last results" card
    reopens the latest search, followed by recent Soulseek searches showing status
    (*Searching · N folders* / *N folders · age*).
  - Recents are a fork DataStore history per scope (`SlskdStores.searchHistory(scope)`,
    50 per scope). Upstream's `recent_search` table and its "sort chronologically" setting
    no longer drive this screen.
  - **Input text** is restored from the user's own draft on every expand, never from the
    SearchBar text (the old query ghost). The bar text means "query of the Library
    results below" and is blank in Soulseek scope.
  - **Entry policy**: a fresh entry auto-expands with the keyboard (upstream). A
    back-stack return never re-expands over results. Library results are re-run (the
    recreated view has empty adapters); otherwise the SearchView opens on recents without
    the keyboard.
  - Submit stays IME action or row tap. The empty-Library CTA reads "Search Soulseek for
    «q»" and submits that query immediately.
- Soulseek results stream live (slskd poll 1.5 s, foreground only), grouped by
  (username, directory) with quality pill (lossless 16/44.1 / hi-res / MP3 320+ / lossy),
  free-slot, speed, queue; default ranking quality → free slot → speed → short queue →
  more files; top-100 render + load more.
- **Filters** (toolbar → bottom sheet): audio types (lossless flac/wav/aiff/wv/ape, lossy
  mp3/m4a/ogg/opus; none ticked = all types), a lossy bitrate range in kbps (min/max, either
  optional; applies to every lossy type, lossless never bitrate-constrained, a lossy file
  with unknown derived bitrate is excluded once a bound is set) and "free upload slot only".
  A group is shown when at least one of its files passes every enabled constraint. Filtering
  is client-side (slskd has no bitrate field; bitrate is derived from size/length). The
  filter set persists across searches and app restarts (`SlskdStores.resultFilters`,
  DataStore JSON) and is the single source of truth the results ViewModel collects.
- **One results screen per search**: submitting a Soulseek search pops back to the Search
  destination before opening the new results (deep link + `popUpTo`), so back from results
  always lands on Search. Earlier result sets are reachable again only through the
  history (below), never by stacking screens. The query travels in the deep link (`?q=`).
  The results toolbar title is the query; tapping it or **Edit search** goes back to Search
  in Soulseek scope with the query ready to refine.
- **Reopen, don't re-run**: the coordinator keeps the last 3 runs in memory, finished ones
  included. Opening a results screen for an id (history row, Last-results card,
  process-death restart) reattaches the in-memory run, or re-reads the search from slskd
  (`GET /searches/{id}` + `/responses`, gateway `reopen`). Only a 404 (*slskd no longer
  has this search*) offers an explicit **Run again**.
  - A superseded or stopped search is told to stop seeking and kept on slskd, never
    DELETEd (D8).
  - Each search has its own fallback result set and remembered hits, so a new search can
    never show the previous query's folders while slskd's `/responses` is catching up.
  - A retry, run-again or restart never flashes the previous run's rows, and the previous
    run's collectors are cancelled.
- Allowlist extensions only (audio + cover images); executables/archives hidden, locked
  files visible but not selectable. Banner "Results from other Soulseek users"; first-use
  privacy sheet shown once.
- Batch endpoints of slskd ≥0.27 are probed first with fallback to the 0.26 per-user
  enqueue; failures (incl. offline peer 500s) surface via snackbar; (username, filename)
  dedupe prevents double-queueing.
- **Load full folder** browses the peer (`POST /users/{u}/directory`, parsed shape-tolerantly:
  `Directory` object, array of directories or bare file array; bare browse filenames get the
  directory prefix). A failed, unparseable or empty live listing falls back to the
  remembered search hits; when that fallback equals what is already shown the sheet says
  "Full folder listing unavailable from this peer" instead of pretending.

### 2.4 Transfers & handoff
- Activity screen: per-folder groups, per-file state rows parsed from slskd's composite
  state tokens (`Completed, Succeeded` / `Aborted` / `Queued, Remotely` …), retry-failed,
  cancel (with remove flag); polling 3 s while resumed with backoff.
- **Clear completed** (visible when any row succeeded or failed; slskd
  `DELETE /transfers/downloads/all/completed`) and **Clear all** (visible whenever rows
  exist; confirmation dialog; slskd has no clear-all endpoint, so every row of a fresh
  snapshot is cancelled + removed via `DELETE /transfers/downloads/{u}/{id}?remove=true`).
- Handoff: on all-files-succeeded → 5 s wait → `startScan` → poll `getScanStatus` ≤2 min →
  `search3` for parsed artist/album → *In library* chip with deep-link open, or honest
  *Downloaded, not visible in library yet* + manual rescan. Auto-scan honors the toggle.
- Server contract documented in `slsk/README.md` §handoff (slskd downloads dir must land
  inside Navidrome's music folder).

### 2.5 Upstream-merge discipline
- New code: `:slsk:core` (no upstream imports), `:slsk:ui` (`slsk_` resource prefix), glue
  sources compiled into `:app` via the S2 hook; hook scripts `slsk/gradle/*.gradle`;
  own catalog `slsk/gradle/slsk.versions.toml`; guard script `slsk/tools/check-seams.sh`
  budget ≤18 files / ≤150 added lines vs upstream merge-base (excl. `slsk/`, fork-doc layer,
  `slsk_*.yml`; raised 16 → 18 for seam S8/S9, the update-check rechannel).
- Upstream edits allowed only at registered seams: S1 `settings.gradle`, S2 `app/build.gradle`,
  S3 `SearchFragment.java`, S4 `fragment_search.xml`, S5 `global_preferences.xml` +
  `SettingsContainerFragment.java`, S6 `nav_graph.xml`, S7 `App.java`, S8 `Github.java`,
  S9 `Preferences.kt`, R1 manifest, R2 default `app_name` + `support_url`-adjacent strings,
  R3 rebrand icon recolors, R4 launcher-adjacent recolors + login shortcut icon
  (`MainActivity.java`) — all recorded with anchors in `slsk/SEAMS.md`.
- Sync: upstream remotes are not auto-synced by CI; on each upstream release create a
  `sync/YYYY-MM-DD` branch off main, merge the upstream tag, re-apply seams per SEAMS.md,
  re-run `slsk/tools/check-seams.sh` + the build gate below.

### 2.6 Verified live environment (acceptance harness)
- Device: Pixel 10a via adb UI automation; servers: slskd 0.26.1.0 (VPS) + Navidrome 0.64.2.
- Golden fixtures under `slsk/contract/fixtures/` drive unit tests (MockWebServer) and the
  seam guard; live-API facts live in `slsk/SPIKE.md`.

### 2.7 Update check (fork release channel)

- The GitHub update check polls the **fork's own release channel**, never upstream:
  `Github.java` queries `pandeiro/Tempore` (API `/repos/pandeiro/Tempore/releases/latest`,
  prerelease `-dev` tags excluded by GitHub automatically). Upstream tags never drive the
  fork's dialog — that was the "attached to upstream" nag: the fork sat below upstream's
  latest version forever (live-checked 2026-11-23: upstream latest published release is
  `v4.32.0`), so every app start (once per 24 h) offered upstream APKs the fork cannot
  even install (different appId/signature).
  **Live fact (2026-11-23)**: `pandeiro/Tempore` is public but has no published GitHub
  releases yet (tags only), so `/releases/latest` 404s → `SystemRepository` leaves the
  LiveData unset → observer no-ops → silent skip until the first stable fork release is
  published (`github_release.yml` publishes one on every non-dev `v*` tag).
- **Opt-in, off by default**: `github_update_check` preference defaults to `false`
  (`Preferences.kt` gate + `global_preferences.xml` SwitchPreference). Fresh installs never
  phone GitHub or show the dialog until the user enables Settings → Updates. The 24-hour
  snooze (`NEXT_UPDATE_CHECK`, set by the dialog's negative button) and the
  version-name/tag comparison (`UpdateUtil.showUpdateDialog`) are unchanged.
- Dialog/UX unchanged: positive button opens the fork release page (`htmlUrl` of the
  matched release), negative snoozes 24 h, neutral opens the support link.
- `degoogled` flavor still never checks (existing flavor gate in `MainActivity`).

## 3. Verification gates (definition of done)

Per change set, all must pass:

```bash
./gradlew assembleTempusDebug assembleDegoogledDebug testTempusDebugUnitTest lintDegoogledDebug
./gradlew :slsk:core:testDebugUnitTest
slsk/tools/check-seams.sh
```

Plus `assembleTempusRelease` whenever the change touches R8-sensitive code (serialization
DTOs, OkHttp, Keystore). End-to-end acceptance (Settings → search → download → in-library)
is exercised at feature milestones against the real servers.

## 4. Decisions log (binding)

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | Polling, not SignalR/push | API key never in URL/query; one dependency less; transfers live server-side |
| D2 | No Room/Hilt/security-crypto in slsk feature | keeps annotation-processor config + schemas + merge surface upstream-pure |
| D3 | Per-user enqueue with batches probe | slskd 0.26.1 lacks `/transfers/downloads/batches` (404) — fallback is the live contract |
| D4 | Deep-link nav into included graph | `<include>` destinations are nested, not id-flattened; deeplinks are the supported route |
| D5 | Scope starts on Library on every fresh Search entry; kept across back-stack returns; never persisted (amended 2026-11-23) | spec §5.2 "never auto-selected"; persisted scope must not hijack Library search, but coming back from Soulseek results must not flip you to Library |
| D8 | Superseded/stopped Soulseek searches are stopped (PUT), not DELETEd | keeps them reopenable from history; slskd's own retention (or its UI) clears them |
| D6 | Epoch millis (no java.time) | upstream ships no core-library desugaring; minSdk 24 |
| D7 | Fork keeps upstream Kotlin package | only appId/label/icon change; seams stay tiny for frictionless upstream merges |
| D9 | Update check is opt-in and polls the fork repo (`pandeiro/Tempore`), never upstream | the fork can never match upstream's version curve, so the default-on check nagged every start with APKs the fork cannot install (different appId/signature); silent network call at every cold start also conflicts with minimal-footprint rebrand |

## 5. Merged spec reference (from the 2026-11-07 drafts; now the only copy)

### 5.1 Topology
```
 Android device                              Home server or VPS
+--------------------------------+          +-------------------------------------+
| Tempore (upstream :app + seams)| Subsonic |  Navidrome                          |
|   + 7 small seams              |--------->|     reads /music                    |
| :slsk:ui    screens, VMs       |          |                                     |
| :slsk:core  gateway, DTOs,     | REST     |  slskd (:5030 / :5031)              |
|             auth, ranking      |--------->|     writes downloads/ --> /music    |
+--------------------------------+ /api/v0  |     <--> Soulseek network (:50300)  |
                                            +-------------------------------------+
```

### 5.2 slskd transport & server hardening
| Transport | When | Notes |
| --- | --- | --- |
| Reverse proxy with real certificate | remote use | URL base supported; enforce IP rules at the proxy |
| VPN/mesh (WireGuard, Tailscale) | remote use | plain HTTP inside the tunnel is fine; slskd never public |
| slskd built-in HTTPS (5031) | LAN | self-signed by default; TOFU fingerprint pin supported by the app |
| Plain HTTP on LAN | convenience | warn + suggest CIDR restriction on the API key |

Server checklist: change default `slskd`/`slskd` credentials; keep `remote_configuration`
and `remote_file_management` off; Swagger/metrics off in production; never expose 5030/5031
to the internet without proxy/VPN; create a dedicated low-privilege API key for the phone
(avoid Administrator role outside LAN); disable completed-download retention so slskd can
never delete files that Navidrome scans.

### 5.3 Client-side rules
- Hand-written Retrofit/OkHttp client, tolerant JSON (`ignoreUnknownKeys`-style)) —
  slskd's generated OpenAPI is unreliable (search endpoints have no response schemas).
- Timeouts: 10 s connect / 20 s read normal; search call `searchTimeout` + 15 s.
- Capability probe on connect (version + fallback endpoint shapes, remembered per session);
  notice (not error) outside the tested version window.
- Idempotency: client-generated GUID search ids; enqueue dedupe by (username, filename)
  against current transfers so a retry cannot double-queue.
- slskd is single-tenant: the app tags its searches with an id prefix and filters on it.
- Polling is foreground-only via `repeatOnLifecycle`; slskd keeps transfers server-side, so
  closing the app never kills a download.
- Never put result sets into Bundles/SavedState — pass ids only (upstream has a
  TransactionTooLargeException history).

### 5.4 State ownership
| State | Owner | Lifetime |
| --- | --- | --- |
| Connection profile (URL, auth, pin) | App (Keystore + encrypted blob) | persistent |
| Search session (id, responses) | slskd | until slskd retention (app never DELETEs, D8); app keeps last 3 runs in memory, finished included |
| Search scope, drafts, last Library query | `SlskSearchScreenState` (SearchFragment-scoped ViewModel) | survives back-stack returns, dies with the fragment; never persisted |
| Grouping, sort | ViewModel | per screen |
| Result filters (types, lossy bitrate range, free slot) | App (DataStore JSON, `result_filters`) | persistent; results ViewModel collects |
| Transfers | slskd | polled, never mirrored |
| Recent completions log | App (DataStore JSON) | 30 days, cap 200 |
| Search history (scoped Library / Soulseek; Soulseek entries keep slskd id + folder count) | App (DataStore `slsk_history`) | persistent, cap 50 per scope |

### 5.5 Error & empty states (contract for every surface)
- Not configured → setup card (no crash freight).
- slskd unreachable/timeout → banner + Retry; Library search unaffected.
- Key rejected (401/403) → banner linking to Settings.
- slskd up but disconnected from Soulseek → banner, submit disabled.
- Zero-responder search → suggest shorter query / loosen filters.
- Enqueue partially failed → per-file failures with Retry failed (read the failures list;
  never trust a 2xx alone).
- Transfer errored/rejected/timed out → row-level reason + Retry.
- Navidrome unreachable at handoff → skip scan, mark Downloaded, offer manual rescan.

### 5.6 Sync procedure (upstream releases)
```bash
git config rerere.enabled true
git fetch upstream --tags
git checkout -b sync/$(date +%F) main
git merge --no-ff vX.Y.Z              # resolve conflicts using slsk/SEAMS.md
slsk/tools/check-seams.sh
./gradlew assembleTempusDebug assembleDegoogledDebug testTempusDebugUnitTest :slsk:core:testDebugUnitTest lintDegoogledDebug
```
Expected conflicts only in seam files. Nightly dry-run merges upstream `development` into a
scratch branch (green/red/conflict report only) — TODO optional for this fork.

### 5.7 Test strategy
- Unit: gateway ↔ MockWebServer with golden fixtures (in `slsk/core/src/test`), mappers,
  grouping/ranking, ViewModels (Turbine); the fake gateway doubles as demo mode.
- Contract: re-record fixtures when upgrading slskd; diff the endpoints/params/status codes
  we use against the recorded snapshot when bumping.
- Instrumented smoke (milestones): settings entry exists, scope toggle appears, fake-server
  flow completes.
- Manual device checklist per release: LAN HTTP; pinned HTTPS; rejected key; slskd stopped
  mid-search; disconnected-from-Soulseek; partial batch failure; long remote queue; app
  killed mid-download; handoff with watcher off; minified release build; both flavors.

### 5.8 Licensing & legal
- GPL-3.0 inheritance stays intact: fork of GPL-3.0 Tempus → keeps GPL-3.0 with notices;
  Navidrome/slskd are separate network processes, not linked. Soulseek queries broadcast
  under the slskd account's name; downloaded material may be copyrighted — personal builds
  only, never Play/F-Droid distribution, alias account and VPN recommended. Not legal advice.

## 6. Development log

### 2026-11-08 — Tempore MVP shipped (release `4.31.0-tempore.1`, versionCode 5201)
- Scaffolding: `:slsk:core` / `:slsk:ui` + hooks (S1/S2), spike notes + redacted fixtures
  from live slskd 0.26.1.0.
- Core: `SlskdGatewayImpl` (block-POST + 1.5 s poll, per-user enqueue fallback, dedupe,
  TOFU pinning, `X-API-Key` redaction), `NavidromeClient` (Subsonic 1.15.0), grouping/ranking
  engines, `KeystoreCredentialStore`, DataStore stores (search history ≤50, completions log
  ≤200/30 d), fake gateway; 43 JVM tests incl. fixture round-trips.
- UI: scope toggle facade, results screen (streaming, filters, top-100, banner, first-use
  sheet), folder sheet (select/load-full/download), activity screen (states, retry/cancel/
  clear, handoff with In-library deep-link or manual-rescan state), settings activity with
  Test connection + debug seeding.
- Seams S1–S7 wired; rebrand R1/R2; launcher icon recolored; fork release keystore wired
  through the S2 hook.
- Verification: full gate green; on-device acceptance passed — real search streamed
  (resp. 169 / 7226 files), folder of 10 FLACs completed 10/10 (~100 s) on a free-slot peer,
  handoff marked `Speak No Evil` *In library* (search3-verified, 6 songs), honest
  not-visible state rendered for folders outside the mount; stock tempus + degoogled builds
  uninstalled, signed release installed as the only instance.
- Fixes from the device run: duplicate results-toolbar menu (removed code inflate), scope
  reset per spec D5, in-app "Tempore" title (R2), crash tee gated to debug builds.

### 2026-11-09 — Docs made canonical; drafts merged & removed
- `PLAN.md` created as the living spec; `docs/spec/product.claude.md` /
  `docs/spec/product.qwen.md` merged into `PLAN.md` §1–§7 and deleted from the tree
  (recoverable from git history).
- Development-log discipline added to `AGENTS.md` (Guidelines #5); fork banner added to
  `README.md`; `PLAN.md`/`README.md`/`AGENTS.md`/`CHANGELOG.md` declared the fork
  documentation layer in the seam budget (`slsk/tools/check-seams.sh` +
  `slsk/SEAMS.md` rules).

### 2026-11-09 — In-app brand color (seam R3)
- `ic_toolbar_tempo.xml` + `ic_splash_logo.xml` gradient stops recolored tempus-red
  `#FFEC4A4A` → tempore sandstone `#FFC98352` (home/drawer toolbar disc, cold-start splash,
  widget placeholder). Budget consciously raised 12 → 13 files; degoogled icon variants
  intentionally untouched; launcher pair superseded by `@mipmap/slsk_launcher`; `logo.xml`
  unreferenced, left as-is.

### 2026-11-09 — ARCHITECTURE.md mergeability explainer
- New plain-language overview (`ARCHITECTURE.md`) of the upstream-merge strategy for readers
  without Android background: three rules, seam model, layering diagram, boundary table,
  sync flowchart, alternatives table. Declared part of the fork documentation layer
  (seam-budget exclusion), referenced from `AGENTS.md` + `README.md`.

### 2026-11-10 — PR review fixes (security + correctness)
- **Strict TLS pinning (#1, high)**: a configured cert pin is now the sole identity — a
  mismatching (or expired/invalid) chain fails the handshake with no system-CA fallback and
  no MITM window even with hostname verification effectively bypassed for LAN-IP slskd.
  Transport rules live in `slsk/core/gateway/SlskTls.kt`; pinned vs. mismatching chains
  proven with real TLS handshakes (okhttp-tls + MockWebServer).
- **Plain-HTTP warning (#2)**: inline ⚠ warning in slskd settings whenever the effective
  URL is `http://` (kept the http-prepend for LAN convenience; `Tailscale`/HTTPS advised).
- **Test connection (#3)** now builds a throwaway gateway for the typed config — never
  persists credentials and always probes what the user typed.
- **Keystore robustness (#4)**: `load()` wraps IV/cipher init into `StoreError`; `install()`
  treats ANY storage failure as surfaceable (setup card), and Settings' save catches and
  displays secure-storage errors instead of crashing.
- **Folder sheet (#5)**: checkboxes can now deselect files (locked stay pinned).
- **TimeSpan parsing (#6)**: day-form `1.02:03:04.5` parses correctly (fixes remaining-time
  display); unit-covered (`SlskdMapperTimeSpanTest`).
- Nits: `SlskdConfig.toString()` redacts the API key; transfers fixture per-file usernames
  fully aliased.

### 2026-11-15 — Feedback round 1 (8 issues)
- **Icons (seam R4)**: `ic_launcher_background/foreground` recolored to `#FFC98352`; login
  dynamic shortcut uses `R.mipmap.slsk_launcher`. Budget raised 13 → 16 files (3 upstream
  files). If a launcher still shows red after reinstall, that is the launcher's icon cache.
- **Filters redesigned** (§2.3): bottom sheet with format chips + lossy bitrate range +
  free slot, persisted in DataStore (`SlskResultFilters` in `:slsk:core`, unit-covered by
  `ResultFiltersTest` + `SlskdStoresTest`); old lossless/320 popup menu removed.
- **Results navigation** (§2.3): deep link now carries `popUpTo(<search destination>)` and
  the query (`?q=`). Id navigation was rejected: `NavController` resolves ids only among the
  current graph's direct children and parents, so the nested `<include>` destination is not
  reachable by id from Search (decision D4 stands). `bindRun` also resets the group cache.
  Device run found the real cause of "results not reset / back stops working": upstream's
  editor-action listener fires on ENTER down AND up, so a second submit pushed a second
  results screen on top of the first (whose run was cancelled → stuck at "0 responders").
  `SlskSeams.onSearchSubmitted` now consumes submits while results are already current.
  Results also hide the leftover search keyboard and got a toolbar up-arrow.
- **Back gesture (seam R1)**: `android:enableOnBackInvokedCallback="false"`. At targetSdk 37
  predictive back is on by default and Material 1.10 `SearchView` leaks its
  `OnBackInvokedCallback` when navigation detaches it mid-hide (unregister uses
  `view.findOnBackInvokedDispatcher()` → null): every later back/swipe was swallowed —
  reproduced on device with the results fragment still RESUMED after a swipe. Same leak
  explains the intermittent album-detail swipe-back failure (album reached from search).
  Verified after the change: swipe-back results → Search (twice, 2nd results never
  reachable), album page → search, settings/library unaffected.
- **Settings**: double hints removed — floating label on the layout, example text as
  `app:placeholderText` (API-key placeholder set only when a key is saved).
- **Load full folder** (§2.3): `userDirectory` returns the raw body, parsed by
  `SlskdMapper.directoryFiles` (the old `List<SearchFileDto>` type could never parse slskd's
  `Directory` object, so live browse never worked); empty live listings no longer mask the
  search-hit fallback; honest "listing unavailable" snackbar. MockWebServer-covered.
  **Live-verified on slskd 0.26.1**: a 1-file search hit (BoinkBonk, Lighthouse) expanded to
  the full 38-file folder (`slsk/SPIKE.md`; raw JSON shape not captured from the release build).
- **Activity** (§2.4): "Clear all" FAB (confirm dialog, per-row cancel+remove); "Clear
  completed" now shows whenever any row finished (succeeded or failed), not only when a whole
  folder succeeded. Both FABs (and the results "Load more" button) were rendered BEHIND the
  host's mini player + bottom nav — the old "Clear completed" was never visible on device.
  Now inset by `@dimen/slsk_host_bottom_inset` (164dp, mirrors upstream
  `global_padding_bottom`). Dialog verified; server-side clear not exercised against the
  user's real queue.
- **Other device-found fixes**: `SlskdStores` production constructor used file names without
  `.preferences_pb` → DataStore threw on first access and filters never persisted (tests now
  go through the production constructor); folder-sheet rows set `isChecked` before detaching
  the recycled listener → wrong file toggled + RecyclerView "computing a layout" crash;
  search-hit memory could list a file twice; filter sheet opens expanded and rides above the
  IME (min → max via IME "next").
- Verified on device: filter (mp3 192–320) applied, survived force-stop + new search, Reset
  clears; settings placeholders vanish on typing; login shortcut icon sandstone (launcher
  uses themed monochrome icons, so the home icon follows the system theme).

### 2026-11-23 — Upstream sync v4.32.0 (Android Auto voice search)
- Issue: "Hey Google, play Thelonious Monk on Tempore" in Android Auto — Gemini announced
  playback, then spoke an error. Cause (v4.31.0 code): Media3 turns the legacy
  `playFromSearch` into `onSetMediaItems`/`onAddMediaItems` with a blank `mediaId` and
  `requestMetadata.searchQuery`; `MediaLibraryServiceCallback` ignored the query and queued
  the blank item (no URI), so playback failed.
- Fix: no fork code — merged upstream **v4.32.0** (branch `sync/2026-11-23`, clean merge, no
  seam conflicts). It brings upstream #1214: voice query → `search3` → `VoiceQueryRouter`
  (exact artist → instant mix; exact album → album; else matching songs with the exact title
  first; near miss → closest artist/album; blank query → random songs). A query with nothing
  playable fails the request so current playback continues. Also brings ~20 upstream fixes
  (large-queue OOM, offline launch with queued downloads, widget oversized art, rating the
  previous song, stream reconnect, scan-status polling #1215 — closes our backlog item).
- Version: hook derives `4.32.0-tempore.N` / versionCode `53xx` automatically.
- Gates: `check-seams.sh` OK (16 files / 47 added lines); `assembleTempusDebug
  assembleDegoogledDebug testTempusDebugUnitTest :slsk:core:testDebugUnitTest
  lintDegoogledDebug` green (363 tests, 0 failures, incl. upstream `VoiceQueryRouterTest` +
  `MediaLibrarySessionCallbackVoiceTest`).
- Pending: device verification in the car (artist, album, song, misheard name, "play some
  music", cold start with Tempore not running, server unreachable). Follow-up only if the car
  test shows it: route on Gemini's structured extras (`EXTRA_MEDIA_FOCUS`, `_ARTIST`,
  `_ALBUM`, `_TITLE`) when the free-text query misses, e.g. "play Round Midnight by Monk".
  Degoogled flavor has no Android Auto (out of scope).

### 2026-11-23 — Search UX epic (branch `feat/search-ux`, one PR)
- Seven field issues traced to code-level root causes (§8.1) and fixed in one change:
  stale previous-query results, no way back to results/downloads, query ghost, Soulseek
  autocomplete, overlong Library candidates, recents ordering/mixing, scope ergonomics.
- Core:
  - Gateway state is per search (fixes I2; MockWebServer regression test fails on the old
    gateway).
  - New `reopen(searchId)` with a `SearchExpiredException` on 404.
  - Superseded searches are stopped, not DELETEd (D8).
  - History is scoped Library/Soulseek with slskd id + folder count.
  - New `SuggestionSanitizer` and `HostBridge.librarySuggestions`.
- UI:
  - New `SlskSearchScreen` controller (§2.3): header toggle + Downloads, per-scope drafts,
    hints and IME flags, recents, candidates, Last-results card, entry policy.
  - The coordinator keeps finished runs and gains `reattach`.
  - Results: title = query, Edit search, expired banner with Run again.
  - Activity gets a deep link.
- Seam S3 reworked: 4 lines changed or added in `SearchFragment.java`. No new upstream
  files; still 16 files. D5 amended, D8 added.
- Fixed in passing: `search_polls_state_groups_results_and_does_not_delete_completed_searches`
  was flaky (the search could complete before the worker's POST landed), so the test now
  waits for the POST.
- Finding while building: coming back to Search through the back stack recreates its view
  with **empty** Library adapters. Upstream's auto-expand-on-every-entry was hiding that
  blank page, so "no auto-expand on return" comes with a re-run of the last Library query
  (§8.2).
- Verification: §3 gate + `assembleTempusRelease` (results in the PR). Device acceptance
  (§8.5) is pending.

### 2026-11-23 — Update check: opt-in + fork release channel (seams S8/S9)
- **Problem**: the GitHub update check (tempus flavor, `MainActivity.checkTempusUpdate()`,
  fires at every app start, throttled to once per 24 h) polled **upstream**
  (`eddyizm/Tempus`, hardcoded in `Github.java`), so the fork compared its `versionName`
  against upstream's release curve (`4.31.0-tempore.N` < upstream `v4.32.0` while the
  fork lagged the sync; upstream's next release would re-trigger it even at parity). The
  dialog nagged on every cold start — offering upstream APKs the fork cannot install
  (different appId `com.github.pandeiro.tempore.*`, fork keystore). Default was on
  (`github_update_check` default `true`), buried in Settings → Updates.
- **Decision (D9, spec §2.7)**: opt-in by default + poll the fork's own releases.
  (Decision renumbered D8 → D9 during the merge: search-UX already took D8.)
- **Implemented (2026-11-23, branch `fix/update-check-opt-in`, worktree
  `Tempore-update-check`, remerged on top of the v4.32.0 sync + search-UX epic)**; diff:
  1. `Github.java` (seam S8): `OWNER` `"eddyizm"` → `"pandeiro"`, `REPO` `"Tempus"` →
     `"Tempore"` — `/releases/latest` ignores prerelease `-dev` tags, so the fork's
     stable channel is the comparison target.
  2. `Preferences.kt` (seam S9): `isGithubUpdateEnabled()` default `true` → `false`.
  3. `global_preferences.xml` (S5 amendment): `SwitchPreference` `defaultValue` `true` →
     `false`.
  4. `values/strings.xml` (R2 amendment): reworded default-locale
     `settings_github_update_summary` to reflect opt-in + fork channel (per-locale
     copies untouched, R2 convention).
  Gates (post-remerge): `assembleTempusDebug assembleDegoogledDebug
  testTempusDebugUnitTest lintDegoogledDebug :slsk:core:testDebugUnitTest`;
  `slsk/tools/check-seams.sh` (budget 18). Device checks pending: fresh install
  (clear data) → no dialog and no GitHub traffic on start; Settings → Updates → enable
  → silent skip while the fork has no published release, then once-per-24-h dialog when
  a stable fork tag > versionName; negative button snoozes.
- **Unchanged on purpose**: 24-h snooze via the dialog's negative button
  (`NEXT_UPDATE_CHECK`), `UpdateUtil.showUpdateDialog` tag comparison (verified
  compatible with the `-tempore.N` versionName suffix: divergence is detected in the
  first two numeric parts; equal versions hit the suffix parse-fail path and return
  false — no false positive), flavor gate (`degoogled` never checks), dialog UX
  (release page / snooze / support link). `support_url` still points at upstream
  ko-fi — left as-is (see backlog).

## 7. Backlog (post-MVP candidates)

- Revisit `POST /transfers/downloads/batches` when the server upgrades past slskd 0.26.x
  (per-user path already tolerates both).
- Queue-position row (`/transfers/downloads/{u}/{id}/position`) surfaced per transfer row.
- Completion notifications (slskd webhook → ntfy or WorkManager), wishlist/standing
  searches, peer-folder browsing, JWT auth strategy, staging importer (beets) deployment
  recipe.
- Translation of `slsk_` strings (lint already tolerates MissingTranslation).
- Upstream generic contribution candidates: pluggable search scopes. (`ScanRepository
  .getScanStatus()` bug was fixed upstream in v4.32.0, #1215.)
- Android Auto voice: structured-extras fallback (see 2026-11-23 entry) if device tests
  show free-text routing misses.

## 8. Search UX epic (2026-11-23 · branch `feat/search-ux`)

Status: **implemented, device acceptance pending** (§8.5). Search is both "play my stuff"
(Library) and the only door to Soulseek downloads; the screen was built for one mode. §2.3
and §4 describe the resulting behavior; this section keeps the root causes, the design
rationale and the acceptance list.

### 8.1 Issues → verified root causes

| # | Field issue | Root cause (in code, 2026-11-23 `main`) |
| --- | --- | --- |
| I1 | No way back to downloads or to the last results without re-running | The Activity screen is reachable only from the results toolbar (`slsk_menu_results.xml`). Search is entered from the Home/Library/Downloads toolbar actions, so leaving it destroys `SearchFragment` and the fragment-scoped `SlskResultsViewModel`. `SlskSearchCoordinator` drops a run from its LRU as soon as the job completes (`invokeOnCompletion → recent.remove`), so a finished search can't be reattached even within the same process. `SlskSeams.observeTransferCount` exists but nothing on the host side calls it. |
| I2 | A new Soulseek search briefly shows the previous query's results | `SlskdGatewayImpl.lastKnownGroups` belongs to the whole gateway, not to one search. During a new search's first polls slskd already reports `responseCount > 0` while `/responses` is still empty or fails, so `groupAndRemember` returns the **previous search's** groups. `lastSearchFiles` also keeps growing across searches with no bound. |
| I3 | The input gets pre-filled with an old term after it was cleared | Material 1.10 `SearchViewAnimationHelper` copies `SearchBar.getText()` into the input on every `show()`. Upstream `search()` pins each Library query and suggestion tap onto the bar (`bind.searchBar.setText(query)`). Clearing the input never clears the bar, and the Soulseek path never updates it, so the old Library term keeps coming back. |
| I4 | Soulseek search runs Library autocomplete | The upstream text watcher calls `setSearchSuggestions` (Navidrome `search3`) no matter which scope is active. IME autocorrect also mangles the partial terms people type for Soulseek. |
| I5 | Very long "artist" candidates | `SearchingRepository.getSuggestions` adds the raw `ArtistID3.getName()`. When the server joins multi-artist tags into one name, the result is a huge string. Nothing limits the length. |
| I6 | Recents on open aren't the most recent, and Library/Soulseek queries are mixed | `sort_search_chronologically` defaults to `false`, which gives `RecentSearchDao.getAlpha()` = `ORDER BY search DESC` (reverse alphabetical) with no limit. Everything goes into one table, so a query first sent to Library (e.g. no results → "Search Soulseek" CTA) also lands there. `SlskdStores.addSearch` exists but is never called. |
| I7 | You can't switch scope from the expanded search screen | The toggle is injected into `search_result_layout`, **behind** the full-screen `SearchView`. `inputFocus()` re-expands the SearchView on every `onCreateView`, including returns via the back stack. `attachScopeToggle` resets the scope to Library on every view creation, including back from Soulseek results. |

### 8.2 Target UX

```
 ┌───────────────────────────────────────────┐   expanded SearchView
 │ ←  Search Soulseek (any words)         ✕  │   hint follows scope
 │ [ Library | Soulseek ]           ⇅ 2      │   header: scope + transfers (active count)
 ├───────────────────────────────────────────┤
 │ ▶ Last results: "geogaddi flac"           │   resume card (Soulseek, empty input)
 │   Searching · 132 folders                 │   live while the run polls
 │ Recent Soulseek searches                  │
 │ ⟲ aphex saw 2                          ✕  │   tap = reopen, not re-run
 │   41 folders · 2 hours ago                │   gone from slskd → "Run again" on results
 └───────────────────────────────────────────┘
```

- **Scope lives inside the expanded SearchView** (`SearchView.addHeaderView`) and stays
  visible while typing. Switching scope keeps the typed text, swaps the hint, the suggestion
  mode and the IME flags, and never submits. When collapsed, the SearchBar hint shows the
  scope.
- **Library mode**: with empty input, show the 6 most recent Library searches, newest first
  (+ "Show all"). From 2 typed characters, show live candidates: artist/album/song,
  ≤ 60 chars each, deduped case-insensitively, at most 10.
- **Soulseek mode**: no autocomplete. The IME uses `TYPE_TEXT_FLAG_NO_SUGGESTIONS`. With empty
  input, show a resume card for the latest search and the next 6 recent Soulseek searches,
  each with its state (*Searching · N folders* / *N folders · age*). Whether slskd still has
  a search is only known when it's opened; expired ones show "Run again". Typing shows
  nothing, and only IME submit runs the search.
- **Transfers are always one tap away** from Search: an icon in the header plus a SearchBar
  menu item, both showing the active-transfer badge.
- **Scope policy (amends D5)**: a fresh entry into Search starts on Library. Coming back
  through the back stack (from results, album, activity) keeps the scope that was in use.
  The scope is never persisted across processes, so it still can't hijack Library search.
- **Auto-expand only on a fresh entry.** Coming back through the back stack never
  re-expands over results. Found while building: the recreated view has **empty** Library
  adapters (upstream's re-expand-on-every-entry was hiding a blank page). So when the last
  submit was a Library search it is re-run (collapsed, results visible again); otherwise the
  SearchView opens on recents / last results without the keyboard. Results → Edit search
  opens Soulseek with the keyboard and the query.
- **Input text** is restored from the user's own draft for that scope (empty if they cleared
  it), never from the SearchBar text. The bar text only means "query of the Library results
  shown below".
- **Library no-results CTA** becomes "Search Soulseek for «q»" and submits right away.
- **Results toolbar**: the title is the query. Tapping it goes back to Search in Soulseek
  scope with the query in the input, ready to refine.

### 8.3 Architecture (as built)

- **`SlskSearchScreen`** (`:slsk:ui`, one per SearchFragment view, tracked weakly in
  `SlskSeams`). It owns the inside of the SearchView: the header (`addHeaderView`), the
  suggestion container, a transition listener, IME flags, and the SearchBar menu + badge.
  - Its state (scope, drafts, last Library query, last submit scope) lives in
    `SlskSearchScreenState`, a ViewModel scoped to the SearchFragment. It survives
    back-stack view recreation and dies with the fragment.
  - **Ghost fix**: Material sets `SHOWING` *before* copying `SearchBar.getText()` into the
    input, and keeps the content invisible until a posted runnable runs. The controller
    marks writes as non-user while `SHOWING` and posts a draft restore, which runs before
    anything is visible.
- **Pure logic in `:slsk:core`** (JVM-tested):
  - `SuggestionSanitizer`.
  - Scoped history: `SlskSearchHistoryEntry` gains defaulted `scope`, `searchId` and
    `folderCount` (old JSON still decodes). The cap of 50 applies per scope, newest first;
    re-submitting moves an entry to the top and takes the new id.
- **Library candidates**: `HostBridge.librarySuggestions(query)` (default empty),
  implemented in `slsk/app-glue` with the same search3 request upstream's
  `SearchingRepository.getSuggestions` makes, run synchronously on IO. Debounced 200 ms;
  stale answers are dropped.
- **Gateway**: per-search `SearchMemory` (fallback groups + remembered hits), last 3 kept;
  folder fallback searches newest first. `reopen(searchId)` covers the cases: complete →
  one emit; in progress → polls; 404 → `SearchExpiredException`.
- **Coordinator**: finished runs stay in the LRU (size-evicted only). A stopped run's last
  snapshot is re-emitted as complete, and the folder count is written to history on
  completion. `start` records the Soulseek history entry. `reattach` tries memory (unless
  that run failed), then `reopen`.
- **Seams: S3 only** (no new upstream files, budget stays at 16 files):
  `attachSearchScreen(...)` replaces `attachScopeToggle(...)`; `inputFocus()` is guarded by
  `shouldAutoShow`; and both upstream suggestion renderers return early via
  `ownsSuggestions`. `onSearchSubmitted` records Library history and falls through for
  Library. `slsk/SEAMS.md` and `check-seams.sh` anchors are updated.
- **Install policy**: always installed. The Library fixes (I3, I5, I6, I7) apply without
  slskd. The toggle, Downloads, Last results and CTA appear only once slskd is configured.
- **Superseded upstream behavior (sync attention)**: upstream's `recent_search` list and the
  `sort_search_chronologically` setting no longer drive the Search screen (upstream still
  writes its table via `setQuery`). Upstream's auto-expand-on-every-entry is replaced by the
  entry policy above.

### 8.4 Delivery

One PR (owner's call, 2026-11-23) instead of the three originally planned. Verification:
the §3 gate, plus `assembleTempusRelease` because the serialized history entry changed.

### 8.5 Device acceptance (Pixel, real servers)
- I2: run two different Soulseek searches back to back; the second never shows the first
  one's folders, including during the first 3 s.
- I1: search → Home → search again → the resume card reopens the same results with no new
  slskd search (check `GET /searches` count); Activity can be opened straight from Search;
  after force-stop, a recent entry reopens from slskd or says *expired*.
- I3: tap a Library suggestion, clear the input, back out, re-enter → the input is empty.
  Switch scopes → each scope keeps its own draft.
- I4/I6: Soulseek mode shows no candidates and no keyboard suggestions; on open, each scope's
  recents list only its own queries, newest first.
- I5: a server artist name over 60 chars never shows as a candidate.
- I7: open Search → switch to Soulseek → submit with no back-navigation; back from results →
  still on Soulseek, SearchView on recents without the keyboard; Library search → album →
  back → collapsed with the Library results re-shown; results → Edit search → Soulseek
  input holds the query.
- Regression: back gesture (R1 notes), flavor parity (tempus + degoogled), Library search
  without slskd configured.

### 8.6 Decisions taken (owner-approved 2026-11-23)
1. The controller installs without slskd (Library fixes for everyone).
2. Superseded Soulseek searches stay on slskd (D8).
3. Upstream `recent_search` rows are not imported: Library history starts fresh. The
   upstream DAO doesn't expose timestamps.
4. The candidate length limit is 60 chars, applied to live candidates only (not to result
   lists).
