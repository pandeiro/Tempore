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
- Search screen gains a Library/Soulseek toggle (injected, zero-upstream-Java besides the
  seam). Submit-only: IME action or suggestion tap. **Scope resets to Library on every
  search-screen entry** (spec §5.2 "never auto-selected" — a persisted Soulseek scope must
  not hijack the library search). The empty-library state shows the "Search Soulseek" CTA.
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
  always lands on Search and earlier result sets are never reachable. The query travels in
  the deep link (`?q=`) so a process-death restart can re-run it; a retry/restart never
  flashes the previous run's rows.
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
  budget ≤16 files / ≤150 added lines vs upstream merge-base (excl. `slsk/`, fork-doc layer,
  `slsk_*.yml`).
- Upstream edits allowed only at registered seams: S1 `settings.gradle`, S2 `app/build.gradle`,
  S3 `SearchFragment.java`, S4 `fragment_search.xml`, S5 `global_preferences.xml` +
  `SettingsContainerFragment.java`, S6 `nav_graph.xml`, S7 `App.java`, R1 manifest,
  R2 default `app_name`, R3 rebrand icon recolors, R4 launcher-adjacent recolors + login
  shortcut icon (`MainActivity.java`) — all recorded with anchors in `slsk/SEAMS.md`.
- Sync: upstream remotes are not auto-synced by CI; on each upstream release create a
  `sync/YYYY-MM-DD` branch off main, merge the upstream tag, re-apply seams per SEAMS.md,
  re-run `slsk/tools/check-seams.sh` + the build gate below.

### 2.6 Verified live environment (acceptance harness)
- Device: Pixel 10a via adb UI automation; servers: slskd 0.26.1.0 (VPS) + Navidrome 0.64.2.
- Golden fixtures under `slsk/contract/fixtures/` drive unit tests (MockWebServer) and the
  seam guard; live-API facts live in `slsk/SPIKE.md`.

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
| D5 | Scope resets Library per entry | spec §5.2 "never auto-selected"; persisted scope must not hijack Library search |
| D6 | Epoch millis (no java.time) | upstream ships no core-library desugaring; minSdk 24 |
| D7 | Fork keeps upstream Kotlin package | only appId/label/icon change; seams stay tiny for frictionless upstream merges |

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
| Search session (id, responses) | slskd | until deleted/retention; app keeps last 3 in memory |
| Grouping, sort | ViewModel | per screen |
| Result filters (types, lossy bitrate range, free slot) | App (DataStore JSON, `result_filters`) | persistent; results ViewModel collects |
| Transfers | slskd | polled, never mirrored |
| Recent completions log | App (DataStore JSON) | 30 days, cap 200 |
| Search history | App (DataStore) | persistent, cap 50 |

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

## 7. Backlog (post-MVP candidates)

- Revisit `POST /transfers/downloads/batches` when the server upgrades past slskd 0.26.x
  (per-user path already tolerates both).
- Queue-position row (`/transfers/downloads/{u}/{id}/position`) surfaced per transfer row.
- Completion notifications (slskd webhook → ntfy or WorkManager), wishlist/standing
  searches, peer-folder browsing, JWT auth strategy, staging importer (beets) deployment
  recipe.
- Translation of `slsk_` strings (lint already tolerates MissingTranslation).
- Upstream generic contribution candidates: pluggable search scopes, `ScanRepository
  .getScanStatus()` bug fix (currently calls `startScan()` upstream).
