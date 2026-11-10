# Phase 0 spike notes (Nov 8 2026, slskd 0.26.1.0 live instance)

Corrects/records observable slskd + Tempus behavior; canonical spec now lives in `PLAN.md` §5
(merged reference).

## Toolchain (upstream actual)
- Gradle wrapper **9.8.0**, AGP 9.2.1, Kotlin **2.2.20**, compileSdk/targetSdk **37**, minSdk 24.
- **Three** flavors: `tempus`, `degoogled`, `play` (play reuses `src/tempus` sources).
- App namespace/package: `com.eddyizm.tempus` (claude spec §11.2's `com.cappielloantonio.tempo` is wrong).
- Version catalog `gradle/libs.versions.toml` auto-imported; no explicit declaration.
- Room via `annotationProcessor`; **no core-library desugaring** → `java.time` unavailable < API 26; use epoch millis.
- `app/build.gradle`: versionCode 52 / versionName '4.31.0' literals; lint baseline + `MissingTranslation` disabled; file ends at line 192.
- `settings.gradle` ends line 26 (`rootProject.name`).

## slskd API contract (live-probed, on 0.26.1.0)
- `POST /api/v0/searches` body needs **`id` as a real GUID** (else 400 "could not be converted to Guid").
  Optional query `?requestTimeout=<ms>` makes the POST block until completion/timeout before returning
  the live SearchResponse snapshot (200). Body: `searchText, searchTimeout, responseLimit, fileLimit` accepted.
- `GET /api/v0/searches/{id}` → `{endedAt,fileCount,id,isComplete,lockedFileCount,loaded?,responseCount,responses,searchText,startedAt,state,token}`.
  `state` = comma-separated tokens, e.g. `InProgress`, `Completed, ResponseLimitReached`.
- `GET /api/v0/searches/{id}/responses` → array; response = `{fileCount,files,hasFreeUploadSlot,lockedFileCount,lockedFiles,queueLength,token,uploadSpeed,username}`.
  File = `{bitDepth,code,extension,filename,isLocked,length,sampleRate,size}` — **no `bitRate`, no `format` field** (lossy bitrate must be derived from size/length).
  Paths are backslash-separated; ended `state: "Completed, ResponseLimitReached"` when responseLimit trips.
- `DELETE /api/v0/searches/{id}` → 204.
- `GET /api/v0/transfers/downloads` → `[{username,directories:[{directory,fileCount,files}]}]`;
  file = `{attempts,averageSpeed,bytesRemaining,bytesTransferred,direction,elapsedTime,endedAt,enqueuedAt,filename,id,percentComplete,remainingTime,removed,requestedAt,size,startedAt,state,username}`;
  state tokens observed: `Completed, Succeeded`, `Completed, Aborted` (composite strings; parse as token sets).
- `POST /api/v0/transfers/downloads/batches` → requires **top-level `Username`** and non-empty `Files` (400 otherwise; note slskd version 0.26.1: `Username` capitalized in validation error). Response code semantics (201 / 207) to be confirmed during acceptance enqueue.
- No `SignalR`; polling sufficient.

## Android integration facts
- Search: `ui/fragment/SearchFragment.java` — submit only via `search(String)` which is ALSO the path for
  suggestion/history taps → scope branch must live in `search()`/`performSearch`, not only the IME listener. TextChanged
  fires suggestions per keystroke (no debounce). VM is activity-scoped (`requireActivity()`).
- Settings: `SettingsContainerFragment` (PreferenceFragmentCompat, `res/xml/global_preferences.xml`). Upstream has **no**
  `OnPreferenceStartFragmentCallback` — `android:fragment` won't work; use the LAN pattern (keyed preference click →
  activity/fragment launch). Launch Soulseek settings as an Activity from `:slsk:ui`.
- Nav: XML `res/navigation/nav_graph.xml` (377 lines); `<include>` insertion before `</navigation>`. Navigate included
  destinations by id (`R.id.slskXxx`). Album deep-link: `R.id.albumPageFragment` + `Constants.ALIBUM_OBJECT`-style bundle
  (`Constants.ALBUM_OBJECT`, `AlbumID3.strippedForNav()`).
- Application: `com.eddyizm.tempus.App` — register HostBridge at end of `onCreate()`.
- HostBridge inputs: `Preferences.getInUseServerAddress()/getUser()/getPassword()/getToken()/getSalt()/isLowScurity()/getActiveMusicFolderId()`
  (note upstream's `isLowScurity` typo). Subsonic version cap is **1.15.0**.
- Cleartext: global `usesCleartextTraffic=true` + trust user CAs → LAN HTTP slskd works; `ACCESS_LOCAL_NETWORK` requested
  app-wide at `BaseActivity` (reuse, do not duplicate).
- ScanRepository bug: `getScanStatus()` actually calls `startScan()` upstream — use own client in `:slsk:core`.
- Branding: `app_name` at `values/strings.xml:57` and repeated in every `values-XX/strings.xml`; launcher icon is
  adaptive **vector-only** (no PNG mipmaps); strategy = resource overlay via app-glue srcDir (verify merge priority), else
  direct edits registered as rebrand seams.

## Fixtures
- `slsk/contract/fixtures/searches.responses.0.26.1.json` (25 responses, usernames aliased PEERxx)
- `slsk/contract/fixtures/transfers.downloads.0.26.1.json` (usernames/redacted)
- `slsk/contract/fixtures/searches.state.completed.0.26.1.json`
