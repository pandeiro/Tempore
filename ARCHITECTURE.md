# ARCHITECTURE.md — how Tempore stays mergeable with upstream Tempus

Plain-language overview of the strategy behind this fork: we want upstream's ongoing
improvements to keep flowing into our app **cheaply and safely**, while we build far beyond
what upstream offers (the entire Soulseek/slskd feature). This document explains how we get
both. It assumes no Android or Gradle experience; terms are defined where first used.

---

## 1. The problem in one paragraph

Tempus is an active upstream project: releases land regularly, files move, code gets
refactored. A typical "fork" copies the whole project, changes whatever it likes, and then
drifts — every upstream release becomes a feared manual merge with no map of *our* changes.
Tempore flips that around: we constrain **every** modification we make to upstream files so
the upstream-vs-fork difference stays small, enumerable, and machine-checkable. Upstream
changes then flow in like a routine pull, not open-heart surgery.

## 2. The three rules

1. **New code lives in our own apartments.** All Soulseek functionality (search, downloads,
   settings) sits in self-contained folders (`slsk/core`, `slsk/ui`) that upstream never
   references — upstream only learns they exist through two one-line build hooks
   (seams S1/S2).
2. **When we must touch an upstream file, we do it in tiny, registered places.** Nine
   anchor points (seams S1–S7 + rebrand seams), one to a few lines each, each recorded with
   a text anchor in `slsk/SEAMS.md`.
3. **Everything else we own outright.** Documentation (`PLAN.md`, this file), fork build
   scripts (`slsk/gradle/*`), tests and fixtures live in areas upstream never touches, so
   they can never conflict with upstream changes.

## 3. What "a seam" looks like

A seam is one deliberate line inside an otherwise untouched upstream file:

```groovy
// app/build.gradle: upstream content ends, then a single line loads our build hook.
// This is the ENTIRE change to this file (seam S2).
apply from: '../slsk/gradle/app-hook.gradle'
```

```java
// SearchFragment.java, seam S3: our module may consume the search before upstream logic runs.
public void search(String query) {
    if (SlskSeams.onSearchSubmitted(this, query)) {
        bind.searchView.hide();
        return;
    }
    // upstream logic continues, untouched, below
}
```

Inert if the feature were deleted: every seam is removable without incident.

## 4. Tour of the app (the whole picture)

Two stories run side by side in Tempore, using different halves of the code.

### 4.1 The music library path (upstream, unchanged)

```mermaid
flowchart LR
    U["user"] --> LOGIN["one-time setup: Navidrome address + credentials"]
    LOGIN --> HOME["Home: discovery, resume points,<br/>mixes for you"]
    HOME -->|"tabs: Library / Download"| LIB["Library: artists → albums → songs<br/>(search Dock: 'search3')"]
    LIB --> PLAY["Queue + play"]
    PLAY -- "streams audio" --> ND["Navidrome (Subsonic API)"]
    PLAY --> SVC["background player (Media3)<br/>notification · lockscreen · widget"]
    ND --> CACHE["on-device cache/database<br/>(artwork, offline copies)"]
```

In words: you sign into a Navidrome server once. The app then lists artists, albums and
songs it exposes over the Subsonic protocol (a plain HTTP/JSON API with token auth) and
plays whatever you pick. Audio playback runs in a background process ("service") so it keeps
going when you leave the app; it drives the notification, lockscreen controls, phones'
Android Auto/Chromecast (tempus flavor) and a home-screen widget.

### 4.2 The Soulseek loop (our feature, see §5–§7)

```mermaid
sequenceDiagram
    autonumber
    actor U as user
    participant APP as Tempore (phone)
    participant SK as slskd (your server)
    participant PEER as Soulseek peers
    participant ND as Navidrome

    U->>APP: Settings: slskd URL + API key → "Test connection"
    APP->>SK: GET /api/v0/application (health)
    SK-->>APP: reachable · authenticated · connected to network
    U->>APP: Soulseek scope search: "coltrane love supreme flac"
    APP->>SK: POST /searches (client-generated GUID)
    loop poll every 1.5 s while results stream in
        APP->>SK: GET /searches/{id} + /responses
        SK-->>APP: peers, files, quality, speed, queue
    end
    U->>APP: open folder card → "Download 10 files"
    APP->>SK: POST /transfers/downloads/{user} (batch)
    APP-->>U: activity screen: folders, progress, remote queue position
    SK->>PEER: download files (this runs for hours/days, server-side)
    PEER-->>SK: files land in slskd's downloads folder (inside Navidrome's music dir)
    APP->>ND: startScan → poll scan status
    ND-->>APP: scan done
    APP->>ND: search3("artist album")
    APP-->>U: "In library" chip → tap = deep-link to the album page (play it now)
```

The key architectural idea repeats: the phone is a controller. Downloads happen **on the
server** (slskd pulls files from peers; the phone stays out of it), so closing the app, or
a parking-garage of a mobile OS killing it, never interrupts a transfer. The phone polls
for state only while open, then re-attaches on next launch.

### 4.3 Where each capability lives

| Capability | Implementation home |
| --- | --- |
| Navidrome browsing / playback | upstream `app` module (untouched) |
| slskd search + results | `:slsk:ui` + `:slsk:core` |
| slskd transfers / activity screen | `:slsk:ui` + `:slsk:core` |
| Soulseek settings + Test connection | `:slsk:ui` (activity built by the S5 seam entry) |
| Navidrome scan / album deep-link | `:slsk:core` client + `slsk/app-glue` navigation |
| Offline / downloads of server tracks | upstream `app` module (untouched) |
| Cast / Android Auto / widget | upstream, flavor-dependent (untouched) |

## 5. How the code is layered

```mermaid
flowchart TD
    subgraph upstream["Upstream Tempus — kept pristine"]
        APP["app module: screens, playback, server login"]
        BUILD["settings.gradle · app/build.gradle · AndroidManifest"]
    end

    subgraph feature["Soulseek feature — fork-owned"]
        CORE[":slsk:core — network client, ranking, credential storage"]
        UI[":slsk:ui — results, folder sheet, activity, settings screens"]
        GLUE["glue: compiled into app, sole translator to upstream"]
    end

    HOOKS["build hooks (slsk/gradle)"]
    DOCS["docs, tests, fixtures"]

    BUILD -- "seams S1/S2: one apply-from line each" --> HOOKS
    HOOKS -- "registers modules" --> CORE
    HOOKS -- "registers modules" --> UI
    APP -- "exports: server address, username, credentials, navigation" --> GLUE
    GLUE --> CORE
    GLUE -.-> UI
    UI --> CORE
    DOCS --- feature
```

Reading it: upstream files stay nearly pristine; our feature talks to upstream only through a
tiny "glue" translator that is allowed (uniquely) to read upstream state like the Navidrome
server address. Nothing in `slsk/core` or `slsk/ui` may import an upstream class — enforced
by the guard script, so drift can't sneak in.

## 6. Who may reach across the boundary

| From | To | Allowed? |
| --- | --- | --- |
| Any upstream file | `slsk/core`, `slsk/ui` | ❌ never (build blocked on imports) |
| Upstream build files | our build hooks | ✅ only via the S1/S2 one-liners |
| Feature modules | upstream app internals | ✅ only `slsk/app-glue` (the translator) |
| Feature modules | each other | ✅ freely inside `slsk/` |

Whenever an upstream file is modified (seams or rebrand), `slsk/SEAMS.md` records its
location, a text anchor that identifies the joining point, and how to re-apply it after
upstream churn.

## 7. A typical upstream sync

```mermaid
flowchart TD
    U["upstream release vX.Y.Z published"] --> FETCH["git fetch upstream --tags"]
    FETCH --> BRANCH["branch sync/YYYY-MM-DD from main"]
    BRANCH --> MERGE["git merge --no-ff vX.Y.Z"]
    MERGE --> CHECK{"conflicts?"}
    CHECK -- "rare; only expected in seam files" --> FIX["re-apply seams per slsk/SEAMS.md"]
    CHECK -- "typical" --> GATE["slsk/tools/check-seams.sh + full build/test/lint gate"]
    FIX --> GATE
    GATE -- green --> SHIP["tag release vX.Y.Z-tempore.N"]
```

Why this is usually painless: all upstream code sits untouched; our delta outside seams is
≤150 added lines across ≤13 files (tracked by the guard script), so merges and conflicts are
rare and small.

## 8. Where new features go

- Pure Soulseek logic (network, ranking, caching) → `slsk/core`, with unit tests.
- Screens and UI surfaces → `slsk/ui` (resources prefixed `slsk_`).
- Anything needing upstream state (credentials, navigation) → `slsk/app-glue`, kept small.
- General bug fixes that would help upstream too → open a PR against upstream instead of
  patching here; the fix then arrives by sync, shrinking our delta instead of growing it.

## 9. Why this over the alternatives

| Approach | Behavior on upstream updates | Verdict |
| --- | --- | --- |
| Copy the whole repo and edit freely | every sync = hand-reviewing hundreds of diffs | rejected |
| Fork with comment markers on each change | same problem, slightly better annotated | rejected |
| A separate app reusing upstream as a library | upstream isn't structured as a library; still a full copy | rejected |
| **Seam-isolated feature modules (ours)** | sync is routine; total forced attention ≈13 files + guard script | chose this |

## 10. Pointers

- `PLAN.md` — canonical spec: features, decisions, verification gates, development log.
- `slsk/SEAMS.md` — every upstream touch with anchors + re-apply notes; rebrand seams R1–R3.
- `slsk/tools/check-seams.sh` — the automated guard (`≤13 files / ≤150 lines + anchor check`).
- `slsk/SPIKE.md` — live-probed slskd API facts (what we worked around).
- `slsk/core`, `slsk/ui`, `slsk/app-glue` — implementation modules.
- CI caveat: upstream CI is disabled in this repo; the guard runs locally (PLAN.md §3).
