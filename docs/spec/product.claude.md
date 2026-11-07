# Tempus + Soulseek: Technical Product Spec

*Draft v0.1 · November 7, 2026 · A personal fork of Tempus (Android) that adds search-and-download through a self-hosted slskd instance, with Navidrome as the library of record.*

## 1. Summary

Tempus is a Subsonic client, and in this setup it talks to Navidrome. This spec adds a second, clearly separate capability: search the Soulseek network through the user’s own slskd server, queue downloads, watch them progress, and see finished albums appear in Navidrome.

Design principles:

1. **slskd is the source of truth.** Searches and transfers live on the server. The app is a thin, mostly stateless client, so closing the app never loses a download.
2. **Additive, not invasive.** All new code lives in separate Gradle modules. Upstream Tempus files are touched only at a small, registered set of seams (budget: at most 12 files and about 150 added lines).
3. **Two search scopes, never blended.** Library search and Soulseek search differ in latency, trust, and available actions, so they get different entry states and visual treatment.
4. **Anti-corruption layer around slskd.** Its API is loosely specified and has drifted. The app talks to an internal `SoulseekGateway` interface, never to raw endpoints from UI code.

## 2. MVP definition

**In scope**

1. Settings: slskd base URL, API key, optional pinned certificate, and a Test connection action (reachable, authenticated, slskd connected to Soulseek, slskd version).
2. A Soulseek search scope inside the existing Search screen (explicit submit only).
3. Results grouped by user and folder, showing format and bitrate, size, free-slot status, queue length, and upload speed. Sort and filter (lossless, 320 kbps and up, free slot only).
4. Download a whole folder or selected files (audio and cover art only).
5. A Soulseek activity screen: per-folder progress, remote queue position, retry, cancel, clear completed.
6. Library handoff: when a folder completes, optionally ask Navidrome to rescan, confirm the album is visible in the Library, and deep-link to it.
7. Honest error states for every hop (section 5.5).

**Out of scope for the MVP** (candidates for later phases, see section 12)

Streaming or previewing a Soulseek file before downloading; browsing a peer’s full shares; chat and rooms; uploads and share management; auto-tagging or import pipelines; wishlist or standing searches; push notifications; Android Auto exposure; multiple slskd profiles; any change to playback, Cast, or the widget.

**MVP acceptance test.** On a device where Tempus already works against Navidrome, a user who enters the slskd URL and key can search for an album, queue a folder of FLAC files, watch it complete, and open it from the Library, without leaving Tempus and without opening the slskd web UI.

## 3. Baseline facts this spec relies on

Checked on November 7, 2026 against the Tempus and slskd repositories and docs, and Navidrome’s Subsonic compatibility page. Items marked **(verify)** come from third-party sources or memory and must be confirmed in the Phase 0 spike (section 12).

**Tempus**

- GPL-3.0. Kotlin is primary with some Java. MVVM-style architecture, Media3/ExoPlayer, Room, Retrofit/OkHttp, Glide, Material 3.
- Two product flavors: `tempus` (Cast and Android Auto) and `degoogled`. Flavor-specific code lives in source sets such as `app/src/tempus/java/...`.
- Toolchain: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, JDK 21 via the Foojay resolver, compileSdk and targetSdk 36, minSdk 24. Dependency versions live in `gradle/libs.versions.toml`.
- Contributions go to the `development` branch; releases are cut from `v*` tags. CI builds but does not run tests, and lint failures have broken the pipeline before.
- The bottom bar is Home / Library / Download, and Download means offline copies of server tracks. **Soulseek downloads must never be labelled Downloads in the UI.**
- Room schemas are committed under `app/schemas/`. Upstream has shipped a fix for a TransactionTooLargeException when backgrounding after deep navigation, and recent releases added local-network permission handling and VPN-aware connectivity checks.

**Navidrome**

- Subsonic API 1.16.1 compatible, including `startScan` (with an extra `fullScan` flag) and `getScanStatus`. A file watcher (default wait 5 s, `Scanner.WatcherWait`) picks up new files; scheduled scans are optional.

**slskd**

- AGPL-3.0, a .NET daemon with a REST API under `/api/v0`. Ports 5030 (HTTP), 5031 (HTTPS, self-signed by default), and 50300 (Soulseek listen port).
- Auth is either a JWT (from a username and password login, default lifetime 7 days) or an API key sent in the `X-API-Key` header. API keys carry a role and an optional CIDR allowlist. Default web credentials are `slskd` / `slskd`.
- Swagger/OpenAPI is off by default (`feature.swagger`). Completed-download events (`DownloadFileComplete`, `DownloadDirectoryComplete`) can be delivered to webhooks or scripts.
- `incomplete/` and `downloads/` default to folders under the app directory and can be relocated. Retention settings can automatically delete completed files.

## 4. Architecture

### 4.1 Topology

```
 Android device                              Home server or VPS
+--------------------------------+          +-------------------------------------+
| Tempus (upstream :app)         | Subsonic |  Navidrome                          |
|   + 7 small seams              |--------->|     reads /music                    |
| :slsk:ui    screens, VMs       |          |                                     |
| :slsk:core  gateway, DTOs,     | REST     |  slskd (:5030 / :5031)              |
|             auth, ranking      |--------->|     writes downloads/ --> /music    |
+--------------------------------+ /api/v0  |     <--> Soulseek network (:50300)  |
                                            +-------------------------------------+
```

### 4.2 Module layout

| Module | Contents | Depends on |
| --- | --- | --- |
| `:slsk:core` | `SoulseekGateway` and `LibraryGateway` interfaces and implementations, OkHttp/Retrofit setup, DTOs and mappers, quality ranking, auth strategies, an in-memory fake slskd server for tests and demo mode | Kotlin, OkHttp, Retrofit, kotlinx.serialization, coroutines. **No Tempus classes.** |
| `:slsk:ui` | Fragments, ViewModels, layouts, `slsk_` resources, nav graph, settings screen, the `SlskSeams` facade, an AndroidX Startup initializer | `:slsk:core`, AndroidX, Material 3 |
| `slsk/app-glue` | A small source directory compiled into `:app` (wired by a hook script): the `HostBridge` implementation that supplies Navidrome server details and navigation callbacks | Tempus internals. This is the only code allowed to import them. |
| `slsk/build-hooks` | Gradle scripts applied by one-line seams | n/a |
| `slsk/gradle/slsk.versions.toml` | Our own version catalog. Upstream’s catalog is never edited. | n/a |

### 4.3 Seams in upstream code

| # | Where | Change | Size |
| --- | --- | --- | --- |
| S1 | `settings.gradle` | `apply from` our settings hook (includes our modules, registers our catalog) | 1 line |
| S2 | `app/build.gradle` | `apply from` our app hook at the end of the file. The hook adds dependencies and the extra source directory, sets the applicationId suffix, version name and code overrides, and lint and R8 settings | 1 line |
| S3 | Search fragment | Attach the scope toggle; on submit, if the scope is Soulseek, hand the query to `SlskSeams` | 2 to 4 lines |
| S4 | Library search empty state | Footer call to action: *Not in your library? Search Soulseek* | 1 to 2 lines |
| S5 | Settings screen | One preference entry that opens the Soulseek settings fragment | 3 lines of XML |
| S6 | Main navigation graph | `include` of our nav graph (the Navigation component supports including a graph from another module; confirm Tempus’s graph is XML) | 1 line |
| S7 | Application class | Register the `HostBridge` | 1 line |

The manifest should need no seam: our manifest merges in from `:slsk:ui`. Cleartext HTTP and local-network permission behavior for LAN hosts must be confirmed in the spike.

**Rule:** no other upstream file may be edited. Each seam is recorded in `SEAMS.md` with its file, anchor text, rationale, and how to re-apply it, and CI fails if the diff exceeds the budget (section 11.2).

### 4.4 Decision: direct-to-slskd or a bridge service

|  | Direct (MVP) | Bridge (later option) |
| --- | --- | --- |
| App talks to | slskd REST API | A small service beside slskd with about six mobile-shaped endpoints |
| Extra moving parts | None | One container |
| slskd API drift | Fixed by shipping a new APK | Fixed server-side, no APK release |
| Exposure | slskd API reachable from the phone | Only the bridge is exposed; slskd stays on localhost |
| Completion handling | The app polls | The bridge receives slskd webhooks and can import, trigger a Navidrome scan, and push notifications |

**Recommendation:** build direct for the MVP, behind `SoulseekGateway`, so a `BridgeGateway` can be added later without touching UI code. If API drift or exposure becomes a burden after the MVP, move to the bridge.

## 5. UX and UI integration

### 5.1 Where each feature lives

| Surface | Change | Upstream impact |
| --- | --- | --- |
| Search screen | A scope toggle (Library and Soulseek). In Soulseek scope the existing search box is reused but is submit-only | Seam S3 |
| Soulseek results | New full-screen destination | None |
| Folder detail | Bottom sheet with a track list and a Download button | None |
| Soulseek activity | New destination, reached from the results toolbar (badge shows active transfers) and from Settings | None |
| Library empty state | Footer call to action | Seam S4 |
| Settings | A *Soulseek (slskd)* entry | Seam S5 |
| Bottom bar, player, Android Auto, widget | Not touched in the MVP. A fourth tab is possible later, at the cost of editing the bottom-nav menu | None |

### 5.2 Telling the two searches apart

| Aspect | Library search | Soulseek search |
| --- | --- | --- |
| Source | Navidrome via Subsonic `search3` | Other people’s shared files, via your slskd |
| Latency | Instant, as you type | Seconds to tens of seconds, results stream in; submit only |
| Result unit | Tracks, albums, artists with tags and art | Folders and files identified by path and filename; no art |
| Primary action | Play, queue, add to playlist | Download (the MVP has no playback) |
| Trust | Yours | Untrusted; quality and tags vary |
| Privacy | Stays between your phone and your server | The query goes out over Soulseek under your slskd account name, and a peer sees your download |
| Look | Standard surfaces | Tertiary-container tint, a Soulseek badge in the top bar, and a persistent banner: *Results from other Soulseek users* |
| Failure modes | Server unreachable | slskd unreachable, key rejected, slskd offline from Soulseek, zero responses |
| Entry rules | Default scope | Never auto-selected, never triggered by typing, never blended into library lists |

A first-use sheet explains once that queries leave your server, that results are other users’ files, and that you are responsible for what you download.

### 5.3 MVP flow

1. **Configure and test.** Enter URL and API key under Settings, then Test connection. The result shows reachable, authenticated, Soulseek connected, and slskd version.
2. **Search.** The user picks Soulseek scope, types, and submits. The app generates a search id (UUID) client-side, starts the search on slskd, and shows progress with a responder count. Results stream in. Cancel stops the search.
3. **Browse results** as release cards (section 5.4).
4. **Open a card** to see the folder sheet. By default it lists only the files that matched. *Load full folder* asks slskd for the peer’s whole directory listing so the entire album can be selected (verify).
5. **Download.** Enqueue in one batch, then show a snackbar with the count and a *View activity* action. Partial failures are reported per file.
6. **Watch activity.** Each folder shows its state. Remote queue position can mean hours or days of waiting, so the screen treats downloads as long-lived jobs rather than spinners.
7. **Handoff.** When every file in a folder has succeeded, and auto-scan is enabled, the app calls `startScan`, polls `getScanStatus`, and then runs `search3` for the artist and album parsed from the folder name. If found, the card shows an *In library* chip and an *Open* action. If not, it shows *Downloaded, not visible in library yet* with a troubleshooting hint (mount, permissions, tags).

### 5.4 Results presentation rules

- **Group key** is (username, directory), where the directory is the file path minus its last segment. Soulseek paths are backslash-separated regardless of the peer’s OS (for example `user\\Music\\Artist\\Album\\01.flac`) (verify).
- **Title** comes from the last one or two path segments, cleaned of brackets and years.
- **Quality badge** from extension plus bit rate, bit depth, and sample rate: Lossless 16/44.1, Lossless hi-res, MP3 320, MP3 VBR, or Other lossy.
- **Default sort:** preferred format, then free upload slot, then upload speed, then shorter queue, then more files (albums before singles). All adjustable; filter choices persist.
- **Allowlist:** flac, mp3, m4a, ogg, opus, wav, aiff, wv, ape, plus cover images. Executables, archives, and unknown types are hidden and never enqueued.
- **Size caps:** send file and response limits to slskd, render the top 100 groups, and load more on demand.
- **In library hint** (should-have): for the top 10 groups only, run `search3` on the parsed artist and album.

### 5.5 Error and empty states

- Not configured: the Soulseek scope shows a setup card.
- slskd unreachable or timing out: banner with Retry; Library search is unaffected.
- Key rejected (401 or 403): banner linking to Settings.
- slskd is up but disconnected from the Soulseek server: banner, and the search field is disabled.
- Search finished with zero responses: suggest shorter terms or dropping the format filter.
- Enqueue partly failed: per-file failures with Retry failed.
- Transfer errored, rejected, or timed out: row-level reason with Retry.
- Navidrome unreachable at handoff: skip the scan, mark *Downloaded*, and offer a manual rescan.

## 6. slskd integration

### 6.1 Authentication and transport

**Default: API key.** There is no login or refresh logic, no username and password on the phone, keys are revocable, and slskd supports a role and a CIDR list per key. A JWT strategy (login, then a Bearer token, re-login on 401) sits behind the same `AuthStrategy` interface as a fallback.

**Key handling**

- Create a dedicated key for the phone and give it the lowest role that permits search and transfers. The docs show ReadOnly and Administrator roles; check whether a middle role exists on your version (verify) and avoid Administrator for any key that leaves your LAN.
- Store the key encrypted with an Android Keystore-backed key. Google has deprecated the androidx security-crypto library, so do not build on it.
- Never log it (OkHttp `redactHeader` for `X-API-Key`) and never put it in a URL. slskd’s SignalR hub takes the key as a query parameter, which is one reason the MVP polls instead.

**Transport options**

| Option | When | Notes |
| --- | --- | --- |
| Reverse proxy with a real certificate | Recommended for remote use | slskd supports reverse proxies and a URL base. CIDR rules on a key will see the proxy’s address, so enforce IP rules at the proxy |
| VPN or mesh (WireGuard, Tailscale) | Recommended for remote use | Plain HTTP inside the tunnel is acceptable; slskd is never public |
| slskd built-in HTTPS (5031) | LAN | The certificate is self-signed by default. Offer trust-on-first-use fingerprint pinning scoped to the slskd client only, never a global trust-all |
| Plain HTTP on LAN | Convenience | slskd documents API keys over plain HTTP as not recommended. Show a warning and suggest a CIDR restriction |

**Server hardening checklist:** change the default `slskd` / `slskd` credentials; keep `remote_configuration` and `remote_file_management` off; leave Swagger and metrics off in production; never expose ports 5030 or 5031 to the internet without a proxy or VPN.

### 6.2 API surface used by the MVP

| Purpose | Call | Notes |
| --- | --- | --- |
| Health and version | `GET /api/v0/application` | Slskd version and Soulseek connection state (verify field names). Used by Test connection and as a pre-search check |
| Start search | `POST /api/v0/searches` | Body: a client-generated `id` (GUID), `searchText`, `searchTimeout` (ms), `fileLimit`, `responseLimit`, `filterResponses`, `minimumResponseFileCount`, `minimumPeerUploadSpeed`, `maximumPeerQueueLength`. Treat the call as possibly long-running: use a dedicated OkHttp client whose read timeout exceeds `searchTimeout`, and begin polling immediately using the client-generated id |
| Search state | `GET /api/v0/searches/{id}` | Progress, response and file counts, and state. Parse state as a set of tokens, not an exact string |
| Results | `GET /api/v0/searches/{id}/responses` | Per peer: username, free slot, upload speed, queue length, and files (name, size, extension, bit rate, bit depth, sample rate, length, locked flag) (verify names) |
| Stop or delete | `PUT` and `DELETE /api/v0/searches/{id}` | PUT stops the search; DELETE removes it |
| Enqueue (preferred) | `POST /api/v0/transfers/downloads/batches` (verify) | Third-party client authors report that the older per-user endpoint is marked obsolete, that the batch endpoint accepts a `searchId`, and that it can return 200 even when every item failed (201 means all queued, 207 means partial). Always read the failures list; never trust a 2xx alone |
| Enqueue (fallback) | `POST /api/v0/transfers/downloads/{username}` | List of filename and size. Used if the batch endpoint answers 404 or 405 |
| Transfers | `GET /api/v0/transfers/downloads` | Grouped user, then directory, then files. Each has an id and a composite state string such as Completed, Succeeded or Queued, Remotely (verify) |
| Queue position | `GET /api/v0/transfers/downloads/{username}/{id}/position` | Shown on queued rows |
| Cancel and clear | `DELETE /api/v0/transfers/downloads/{username}/{id}` (with a remove flag) and `DELETE /api/v0/transfers/downloads/all/completed` | Cancel one, or clear finished |
| Full folder | `POST /api/v0/users/{username}/directory` (verify) | Body names the directory path |

**Navidrome (Subsonic, token auth):** parameters `u`, `t` (md5 of password plus salt), `s`, `v=1.16.1`, `c`, `f=json`. Calls: `search3`, `startScan`, `getScanStatus`. Credentials come from Tempus’s existing server configuration through `HostBridge`, so the user never enters them twice.

### 6.3 Client rules

- **Hand-written** Retrofit/OkHttp client with kotlinx.serialization (`ignoreUnknownKeys`, `coerceInputValues`). Third-party notes say slskd’s generated OpenAPI is unreliable (search endpoints declare no response schemas, a version placeholder appears literally in some paths, and security schemes are missing), so generated clients are not an option.
- DTOs stay inside `:slsk:core`. The UI only sees domain types (`ResultGroup`, `RemoteFile`, `TransferGroup`, `QualityClass`).
- **Capability probe on connect:** read the version, try the preferred call shapes, remember fallbacks for the session, and show a non-blocking notice if the version is newer than the highest tested.
- **Idempotency:** client-generated search ids, and enqueue de-duplicates by (username, filename) against current transfers so a retry cannot double-queue.
- **Timeouts:** normal calls 10 s connect and 20 s read; the search call uses `searchTimeout` plus 15 s.
- **Polling (foreground only, via `repeatOnLifecycle`):** search state every 1.5 s, transfers every 3 s, scan status every 2 s for up to 2 minutes, with exponential backoff on errors.

```kotlin
interface SoulseekGateway {
    suspend fun health(): Health
    fun search(query: String, options: SearchOptions): Flow<SearchProgress>
    suspend fun folderContents(username: String, directory: String): List<RemoteFile>
    suspend fun enqueue(username: String, files: List<RemoteFile>, searchId: String?): EnqueueResult
    fun transfers(): Flow<List<TransferGroup>>
    suspend fun cancel(username: String, transferId: String, remove: Boolean)
    suspend fun clearCompleted()
}
```

## 7. State management

| State | Owner | Lifetime | Where it lives |
| --- | --- | --- | --- |
| Connection profile (URL, auth mode, pinned fingerprint) | App | Persistent | DataStore; the secret is encrypted with a Keystore key |
| Search session (id, responses) | slskd | Until deleted, or slskd retention | In-memory LRU cache (last 3 searches) in a singleton repository. Only the search id goes into `SavedStateHandle` |
| Grouping, sort, filters | ViewModel | Per screen; preferences persist | Memory; filter defaults in DataStore |
| Transfers | slskd | slskd retention (indefinite unless configured) | Polled, not mirrored |
| Recent completions log (folder, user, finish time, library status) | App | 30 days, capped at 200 | DataStore JSON |
| Search history | App | Persistent, capped at 50 | DataStore |

**Rules**

- Never put result sets in Bundles, fragment arguments, or saved state. Upstream has already needed a fix for TransactionTooLargeException. Pass ids instead.
- slskd is authoritative. Reconcile from the server whenever a screen opens. The local completions log exists only because slskd retention can purge finished transfers.
- One process-scoped `SoulseekSession` owns the gateway, polling jobs, and caches. It is started by an AndroidX Startup initializer, so screens can come and go.
- slskd is single-tenant. If several people share one instance, everyone sees everyone’s searches and transfers. The app tags its own searches with an id prefix and filters on it; the activity screen can optionally show all.
- No Room in the MVP. That avoids coupling to upstream’s annotation-processor setup and keeps `app/schemas/` untouched. If tracked state grows richer, add Room in a separate database file with its own schema directory.

## 8. Library handoff

### 8.1 The file-landing contract (server side)

The app cannot move files. slskd writes them on the server, so the deployment has to satisfy a contract.

| Option | Layout | Pros | Cons |
| --- | --- | --- | --- |
| A. Direct (MVP) | slskd `directories.downloads` points to a folder inside Navidrome’s music folder, for example `/music/Soulseek`. `incomplete` stays outside | No extra services; the watcher picks files up | Badly tagged files land in the library; no de-duplication |
| B. Staging plus importer | slskd downloads to a staging folder. A `DownloadDirectoryComplete` webhook triggers an importer (beets or a custom script) that tags, renames, and moves files into `/music` | Clean library; a place for virus scanning | An extra service to build and run |
| C. Relay agent | slskd’s relay agent mode can receive completed downloads on another machine | Library host differs from slskd host | More setup |

**Required settings for option A**

- Both containers see the same bind mount. The slskd user can write to it (PUID/PGID, `SLSKD_UMASK`, or the permission-mode option) and Navidrome can read it.
- Disable slskd file retention for completed files, otherwise slskd can delete files from your library.
- Keep `incomplete` outside the music folder so Navidrome never sees partial files.
- Keep the Navidrome watcher enabled (`WatcherWait` above 0). Watchers can miss events on network filesystems and some bind mounts, which is why the app can also call `startScan`.

### 8.2 Completion and verification

A folder group is complete when every transfer in it has succeeded. If any are errored, rejected, or timed out, the group is partially complete and offers Retry failed. After completion: wait about 5 s, call `startScan` if enabled, poll `getScanStatus`, then query `search3`, and mark the group *In library* or *Not visible yet*. None of this blocks the UI.

### 8.3 Hardening for later

Webhook-driven importer and notifications; antivirus scanning of staged files. If slskd scripts are used, read the event from the `SLSKD_SCRIPT_DATA` environment variable and validate every field. The slskd docs warn that filenames and usernames come from the network and can be crafted to inject shell commands.

## 9. Technical challenges and risks

| # | Challenge | Why it matters | Mitigation |
| --- | --- | --- | --- |
| 1 | Search is slow, noisy, and large | Thousands of files from hundreds of peers. Soulseek search is a substring match over the whole path, so accidental matches are common | Group by folder, send file and response limits, rank, render the top 100, parse off the main thread, keep results out of saved state |
| 2 | slskd API instability | The OpenAPI document has gaps, the per-user download endpoint is reported obsolete, and the batch endpoint can answer 200 when everything failed | Hand-written client behind a gateway, fallbacks, read failure lists, contract tests (11.6), bridge option (4.4) |
| 3 | Files land in the wrong place | Navidrome cannot see them, or slskd retention deletes them | Deployment contract (8.1) with a reference compose file and troubleshooting guide; the handoff status tells the user when a folder is not visible |
| 4 | Library refresh is unreliable | Watchers can miss events on network filesystems and some bind mounts | `startScan` fallback with status polling and a clear *not visible yet* state |
| 5 | Tags and names from strangers | Navidrome organizes by tags, so badly tagged files fragment or hide | Importer option B (beets) later; treat filename-derived titles as hints only |
| 6 | Credential exposure | API keys do not expire, default credentials are well known, and CIDR rules are bypassed behind a proxy | Section 6.1: dedicated key, least role, Keystore storage, proxy or VPN, no key in URLs |
| 7 | Untrusted files | Malware disguised as audio, or crafted files aimed at media parsers | Extension allowlist, no archives or executables, server-side antivirus, keep Navidrome updated, no files opened on the phone in the MVP |
| 8 | Privacy and legal | Queries are broadcast under your slskd username, downloaded material may be copyrighted, and many peers deprioritize users who share nothing | First-use notice; consider an alias account; route slskd through a VPN yourself (its VPN integration only monitors status); keep builds personal and off Play and F-Droid; decide deliberately about sharing. The user is responsible for what they download. Not legal advice |
| 9 | Network-side rate limiting | The Soulseek server and peers can throttle or ban chatty clients | Submit-only search, one active search at a time, re-running history needs a tap, cancel on exit |
| 10 | Android background limits | Doze and background restrictions stop polling | Transfers continue on the server; poll only in the foreground; Phase 2 adds WorkManager checks or webhook push |
| 11 | Local-network and cleartext rules | Recent Android versions gate LAN access behind a permission (Tempus already handles this for Navidrome), and plain HTTP to LAN hosts needs a cleartext allowance | Confirm in the spike that the existing permission flow also covers the slskd host and that cleartext to LAN is allowed; reuse rather than duplicate |
| 12 | Upstream churn | Search, navigation, and settings screens change often, and AGP and Kotlin versions move quickly | Seam discipline, follow upstream’s toolchain exactly, no private versions of shared libraries |
| 13 | Lint, R8, and translations | Tempus runs lint in CI and ships 14 languages, so new default-language strings are likely to trigger MissingTranslation. R8 can strip reflection-based code | The app hook relaxes `MissingTranslation` for our resources; consumer ProGuard files in our modules; always test a minified release build |
| 14 | minSdk 24 | `java.time` needs desugaring below API 26 | Use epoch milliseconds, or confirm desugaring is enabled |
| 15 | Co-installation | A fork signed with our key cannot upgrade the stock app | Different applicationId, separate data, documented |

## 10. Dependencies

### 10.1 Inherited from Tempus (do not override)

JDK 21, Gradle wrapper 9.4.1, AGP 9.2.1, Kotlin 2.2.10, compileSdk and targetSdk 36, minSdk 24, AndroidX and Material versions from upstream’s catalog, the Navigation component, OkHttp and Retrofit, JUnit 4, Mockito, and MockK.

Our own catalog pins OkHttp and Retrofit to the versions upstream resolves at each sync (CI prints both). If they diverge, Gradle picks the higher one, which usually works but can change the behavior of upstream’s networking code.

### 10.2 New Android dependencies

| Dependency | Module | Purpose | Notes |
| --- | --- | --- | --- |
| OkHttp (logging interceptor in debug only) | core | HTTP, certificate pinning, interceptors | Redact `X-API-Key` |
| Retrofit with the kotlinx-serialization converter | core | Typed endpoints | Tolerant JSON settings |
| kotlinx-serialization-json plus the Kotlin serialization compiler plugin | core | JSON parsing | Plugin version must equal the Kotlin version; bump together |
| kotlinx-coroutines-android | core, ui | Async and Flow | n/a |
| AndroidX Lifecycle (ViewModel, runtime) | ui | State holders, lifecycle-aware polling | n/a |
| AndroidX DataStore (Preferences) | core | Settings, history, completions log | No annotation processor |
| AndroidX Startup | ui | Start the session without editing the Application class | n/a |
| AndroidX Navigation and Material Components | ui | Included nav graph; segmented button, chips, bottom sheets, cards | Match upstream’s versions |
| Android Keystore (platform API) or Tink | core | Encrypt the API key | Not security-crypto (deprecated) |
| WorkManager | core | Phase 2 background checks | Later |
| JUnit 4, MockK, MockWebServer, Turbine, AndroidX Test and Espresso | tests | Unit and instrumented tests | JUnit and MockK already in Tempus |

### 10.3 Development environment

An Android Studio release that supports AGP 9.2; Android SDK platform 36; JDK 21 provisioned by Gradle (no system JDK needed); Docker for a local slskd plus Navidrome stack and the contract job; a free Soulseek account for realistic testing; a physical Android device; GitHub Actions; and our own signing keystore.

### 10.4 Server side

slskd (current stable Docker image), Navidrome (current), a shared volume, a reverse proxy or VPN, and port 50300 forwarded for good Soulseek connectivity (the slskd docs link a misconfigured listen port to poor search results and failures when browsing some users). Optionally gluetun for VPN, and later beets and ClamAV.

### 10.5 Deliberately avoided

A SignalR client (the API key travels in the query string and it adds a dependency; polling suffices for the MVP); androidx security-crypto (deprecated); Room and its annotation processor in the MVP; generated OpenAPI clients; any global trust-all TLS; copying slskd code or UI.

### 10.6 Licensing

Tempus is GPL-3.0, so the fork and any distributed builds must stay GPL-3.0, with source available and upstream notices kept. Navidrome (GPL-3.0) and slskd (AGPL-3.0) run as separate processes reached over the network and are not linked into the app. This is not legal advice.

## 11. Software development lifecycle and upstream updates

### 11.1 Repository model

- Fork `eddyizm/tempus` and add a remote named `upstream`.
- Mirror branches `upstream-main` and `upstream-development` are never committed to. They are fast-forward only, updated by a scheduled workflow.
- `main` is our product: an upstream release plus seams plus `slsk/`.
- `feat/*` branches merge into `main` by pull request. `sync/YYYY-MM-DD` branches carry upstream merges.

### 11.2 Layers and guardrails

- **Layer 0, upstream code:** untouched except for seams.
- **Layer 1, seams:** registered in `SEAMS.md`, tiny, with commit messages prefixed `seam:`.
- **Layer 2, everything new, under `slsk/`:** our own package namespace, a `resourcePrefix` of `slsk_`, our own version catalog, tests, and workflows (`slsk_*.yml`).

Three guardrails run in CI:

1. **Diff budget.** Everything outside `slsk/` must stay within 12 files and 150 added lines relative to upstream.
2. **Anchor check.** Every anchor string recorded in `SEAMS.md` must still exist in its file.
3. **Import boundary.** A grep fails the build if code under `slsk/core` or `slsk/ui` imports Tempus packages (`com.cappielloantonio.tempo`). Only `slsk/app-glue` may.

```bash
BASE=$(git merge-base HEAD upstream/main)
git diff --numstat $BASE HEAD -- . ':(exclude)slsk' ':(exclude).github/workflows/slsk_*.yml' | awk '{f++; a+=$1} END {print f, a; if (f>12 || a>150) exit 1}'
```

### 11.3 Upstream sync procedure

**Cadence**

- **Nightly dry run:** a scheduled workflow merges `upstream/development` into a scratch branch, runs the full CI, and reports green, red, or the conflicting files. It never pushes to `main`.
- **Real sync on each upstream release tag** (`v*`), since upstream cuts releases from `main`.
- **Out of band:** security fixes in shared libraries.

**Steps**

```bash
git config rerere.enabled true
git fetch upstream --tags
git checkout -b sync/$(date +%F) main
git merge --no-ff vX.Y.Z          # an upstream release tag
# conflicts should appear only in seam files; resolve using SEAMS.md
tools/check-seams.sh
./gradlew assembleTempusDebug assembleDegoogledDebug testDegoogledDebugUnitTest :slsk:core:testDebugUnitTest :slsk:ui:testDebugUnitTest lintDegoogledDebug
```

Then run the emulator smoke test, work through the manual device checklist (section 13), open a pull request into `main`, merge, and tag `vX.Y.Z-slsk.1`.

**Why merge rather than rebase.** Our delta is tiny, so conflicts are rare either way. Merging keeps published tags stable, avoids rewriting history that builds were cut from, and keeps `git bisect` meaningful across upstream and our changes.

**What typically breaks, and where the fix goes**

| Upstream change | Impact | Fix location |
| --- | --- | --- |
| Search screen refactor, or a move to Compose | Anchors for S3 and S4 fail | Re-apply the few lines; if Compose, rewrite only the attach adapter in `SlskSeams` |
| Navigation graph restructure | S6 include is lost | Re-add the include |
| Settings screen change | S5 entry is lost | Re-add the entry |
| Kotlin, AGP, or Gradle bump | Hook scripts or serialization plugin version break | Bump the plugin to the same Kotlin version; fix the hook scripts |
| Dependency bump (OkHttp, Retrofit, Media3) | API changes affect our client | Adapt inside `:slsk:core` only |
| Server configuration or preferences class change | `HostBridge` breaks | Edit `slsk/app-glue` only |
| Large edit to `app/build.gradle` | S2 conflict | Re-append the one-line `apply from` |
| Flavor or variant renamed | Hook scripts reference old names | Edit the hook |

### 11.4 CI/CD

- **On every pull request:** build `tempus` and `degoogled` debug, run unit tests (ours plus upstream’s tests that run today), lint, the seam checks, and one minified release build (R8).
- **Nightly:** the dry-run upstream merge, the contract job (11.6), and an emulator smoke test against the fake slskd server.
- **Release:** a `vX.Y.Z-slsk.N` tag triggers `slsk_release.yml`, which builds release APKs, signs them with our keystore from repository secrets, attaches mapping files, and publishes a GitHub release. Upstream’s own workflows are disabled in the fork’s Actions settings rather than edited, since editing them would add conflict hotspots.

### 11.5 Versioning, signing, distribution

- versionName is `<upstream>-slsk.<n>` (for example `4.29.0-slsk.1`). versionCode is the upstream versionCode times 100 plus n, set in the app hook (confirm in the spike that the hook can read upstream’s value).
- An applicationId suffix of `.slsk` (or your own) lets it install beside stock Tempus. Because upstream’s signing key is not ours, an in-place upgrade from the stock app is impossible, and the two apps keep separate data.
- Back up our keystore. Losing it forces every install to be replaced.
- Self-update with Obtainium pointed at the fork’s GitHub releases, as upstream documents for stock Tempus.

### 11.6 Drift in slskd and Navidrome

- **Weekly contract job:** start the pinned and latest slskd containers with `SLSKD_SWAGGER=true` and `SLSKD_NO_CONNECT=true`, fetch the OpenAPI JSON (third-party notes place it at `/swagger/v0/swagger.json`; verify), and diff the endpoints, parameters, and status codes we use against a committed snapshot in `slsk/contract/`. A diff opens an issue.
- **Golden fixtures** recorded from a real instance (search, results, transfers, and batch enqueue with 200, 201, and 207) are replayed by MockWebServer in unit tests. Re-record them when upgrading slskd.
- **App-side version window:** `testedMin` and `testedMax` constants. Outside the window the app shows a notice, not an error.
- **Navidrome:** only stable Subsonic calls (`search3`, `startScan`, `getScanStatus`). The nightly job exercises them against a current Navidrome container.

### 11.7 Relationship with upstream

Fix generic bugs found along the way as normal pull requests against upstream’s `development` branch, which shrinks the fork delta. Optionally ask the maintainer whether a generic extension point (such as pluggable search scopes) would be welcome, and expect the Soulseek feature itself to stay in the fork.

## 12. Phases and exit criteria

| Phase | Goal | Exit criteria |
| --- | --- | --- |
| 0. Spike (about 2 to 3 days) | Validate seams and API assumptions against a real slskd | Hook scripts build both flavors; seam anchors located in Search, Settings, and the nav graph; real search, batch enqueue, directory listing, and state strings recorded as fixtures; confirmed: blocking behavior of the search POST, the lowest API-key role that works, cleartext and local-network behavior, and that the app hook can set versionCode |
| 1. MVP (roughly 3 to 4 weeks part time) | Everything in section 2 | Acceptance test passes on a physical device; seam budget respected; CI green on both flavors |
| 2. Quality of life | Completion notifications (slskd webhook to a push service such as ntfy, or WorkManager), wishlist searches, browsing a peer, richer in-library hints, JWT auth, a reference staging-importer stack | Each shipped behind a setting |
| 3. Hardening | Bridge gateway, Room for richer history if needed, translations, an optional fourth tab | To be defined after the MVP |

The time estimates are rough and assume one developer who already knows Android.

## 13. Test strategy

- **Unit:** the client against MockWebServer using golden fixtures; mappers; ranking and grouping; ViewModels with Turbine. The fake slskd server doubles as a demo mode for UI work.
- **Contract:** section 11.6.
- **Instrumented smoke (nightly, emulator):** the Settings entry exists, the scope toggle appears, and a fake-server search-to-download flow completes.
- **Manual device checklist per release** (upstream’s guidelines call for physical devices for network features): LAN HTTP; HTTPS with a pinned fingerprint; a rejected key; slskd stopped mid-search; slskd disconnected from Soulseek; a partial batch failure (207); a long remote queue; the app killed mid-download (the server keeps going); handoff with the Navidrome watcher off; a minified release build; both flavors.

## 14. Open questions for the owner

1. Do slskd and Navidrome share a host and a volume (option A), or do they run apart?
2. How will the phone reach slskd away from home: reverse proxy, VPN, or LAN only?
3. One user, or several people on one slskd?
4. Must files be library-ready on arrival, or is a server-side importer for tag cleanup acceptable?
5. Which flavor will you run? `degoogled` is simpler; `tempus` keeps Cast and Android Auto.

## 15. Sources

- Tempus repository and AGENTS.md: https://github.com/eddyizm/tempus and https://github.com/eddyizm/tempus/blob/main/AGENTS.md
- Tempus usage guide: https://raw.githubusercontent.com/eddyizm/tempus/main/USAGE.md
- Tempus releases: https://github.com/eddyizm/tempus/releases/
- slskd README and configuration docs: https://github.com/slskd/slskd and https://github.com/slskd/slskd/blob/master/docs/config.md
- Navidrome Subsonic API compatibility: https://www.navidrome.org/docs/developers/subsonic-api
- Third-party slskd client notes (OpenAPI findings, endpoints, field names): https://glama.ai/mcp/servers/vectorless/slskd-mcp/tree, https://slskd-api.readthedocs.io/en/v0.1.3/api.html, and https://deepwiki.com/LumePart/Explo/5.2-slskd-downloader
