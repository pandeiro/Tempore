# Technical Product Specification: Tempus Integration with slskd (Soulseek)

## 1. Executive Summary
This document outlines the technical specification for customizing **Tempus** [[1]], an open-source, native Android music client built for the Subsonic API (Navidrome), to integrate **slskd** [[7]], a headless Soulseek daemon. The goal is to create a unified "Personal Music Hub" experience where users can seamlessly search the public Soulseek P2P network, initiate downloads to their personal server, and instantly stream the newly acquired tracks via Navidrome within the same Android interface.

## 2. MVP Functionality
The Minimum Viable Product (MVP) will focus on bridging the search and download workflows between the Android client, the slskd server, and the Navidrome backend.

### Core MVP Features:
1. **Unified Search Interface:** Users can input a query and toggle between their local Navidrome library and the global Soulseek network.
2. **Server-Side Downloading:** Initiated downloads are fetched by the slskd server (running on the user's local network/VPS) to a staging folder, rather than directly to the Android device's internal storage.
3. **Asynchronous State Tracking:** The app tracks the status of active slskd downloads (Queued, Downloading, Completed, Failed) and reflects this state in the UI.
4. **Library Ingestion (Sidecar Process):** Once a download completes, an automated background process on the server tags the audio file, renames it, and moves it to the Navidrome media directory, making it instantly playable via the existing Tempus player.

## 3. UI/UX Integration
To maintain the clean, native feel of Tempus, the integration must not disrupt the existing user flow.

### Differentiating Search Sources
The existing Tempus search screen will be modified to include a **Segmented Control (Tabs)** at the top of the search view:
*   **Tab 1: Local Library (Navidrome):** Queries the Subsonic `search3` endpoint. Results display standard Album Art, Artist, and a "Play" icon.
*   **Tab 2: Soulseek (slskd):** Queries the slskd API. Results display the Filename, Bitrate, File Size, and the Username of the peer sharing the file.

### The Download Workflow
1.  **Selection:** When a user taps a Soulseek search result, a bottom sheet slides up displaying the directory tree (if available) or individual file details.
2.  **Action:** The user taps "Download to Server."
3.  **Feedback:** The UI displays a "Download Initiated" toast.
4.  **Queue Management:** A new "Downloads" icon appears in the main navigation drawer or bottom bar. Tapping this opens a list of active and completed transfers, syncing with the server-side state.

## 4. System Architecture & Integration Details

### 4.1 Authentication
*   **Navidrome:** Remains unchanged; handled via existing Subsonic token/password or URL parameters stored in `EncryptedSharedPreferences`.
*   **slskd:** Added to the Tempus "Settings" screen. The user provides:
    *   **slskd Base URL:** (e.g., `https://slskd.mydomain.com` or local IP).
    *   **API Key:** Generated via `openssl rand -base64 48` and configured in the slskd `slskd.yml` file.
    *   **Transport:** The API key is injected into the `X-API-Key` HTTP header via a custom Retrofit Interceptor for all slskd requests.

### 4.2 Relevant APIs
Tempus uses **Retrofit** for network operations [[36]]. We will introduce a new `SlskdApiService` interface.

*   **Search:**
    *   `POST /api/searches`: Sends `{ "query": "artist album" }`. Returns a Search ID/Token.
    *   `GET /api/searches/{id}`: Polls for results. Because Soulseek searches are P2P, results stream in over time; the app must handle paginated or chunked JSON responses until the search completes.
*   **Download:**
    *   `POST /api/downloads`: Submits the specific peer username and filename.
*   **State/Transfers:**
    *   `GET /api/transfers/downloads`: Returns the status, speed, and bytes transferred for all active/queued downloads.

### 4.3 State Management
Soulseek downloads are asynchronous and can take seconds or days depending on peer availability.
*   **Local DB:** Use **Room** to store a local `Transfer` entity (tracking Filename, slskd ID, Status, Progress).
*   **Sync Worker:** Use Android **WorkManager** to run a periodic `SyncWorker` (every 15-30 minutes) that queries the slskd `/api/transfers/downloads` endpoint and updates the local Room DB.
*   **Reactive UI:** Jetpack Compose views observe the Room DB via `StateFlow` or `LiveData`. When the `SyncWorker` updates the database, the UI automatically re-renders to show progress bars or "Completed" checkmarks.

## 5. Technical Challenges & Solutions

| Challenge | Description | Proposed Solution |
| :--- | :--- | :--- |
| **Metadata & Folder Structure** | slskd downloads raw files with arbitrary names (e.g., `01-track.mp3`) and no folder structure. Navidrome requires `Artist/Album/Track` to scan correctly. | **Sidecar Service:** Run a lightweight script (e.g., Beets, Lidarr, or a custom Python watcher) on the server. It monitors the slskd download folder, auto-tags files using acoustic IDs, and moves them to the Navidrome media folder. |
| **P2P Unreliability** | Peers may disconnect, queue the request, or upload at very slow speeds. | **Graceful Degradation:** The UI must display "Waiting on Peer" and "Queued" states instead of just "Failed." Implement a retry mechanism in the server-side watcher or app. |
| **Security Exposure** | Exposing slskd API and Navidrome to the mobile internet poses security risks. | **Reverse Proxy:** Mandate that slskd is exposed via HTTPS (using Nginx/Traefik and Let's Encrypt). The app must enforce strict TLS certificate validation. |
| **Data Usage** | Downloading high-quality FLACs over cellular data can be expensive. | **Network Constraints:** Configure the `WorkManager` sync to only run on unmetered (Wi-Fi) connections, or add a toggle in the app to "Only download on Wi-Fi." |

## 6. Dependencies

### Android Client (Tempus Customization)
*   **Retrofit2 & OkHttp:** For defining the `SlskdApiService` and handling API Key headers.
*   **Room Database:** To persist transfer states and search history locally.
*   **WorkManager:** For background syncing with the slskd daemon.
*   **Kotlinx Coroutines:** For non-blocking API calls and polling loops.
*   **Jetpack Compose:** For building the segmented search UI and download queue screens.
*   **EncryptedSharedPreferences:** For securely storing the slskd API Key.

### Server (Sidecar Requirements)
*   **slskd:** Running in Docker or natively.
*   **Auto-Tagger/Mover:** `beets` (with `import` set to watch the slskd directory) or a custom Python script using `mutagen` to read tags and move files to Navidrome's `/music` volume.

## 7. SDLC & Upstream Update Strategy

To minimize the pain of merging updates from the original Tempus repository, we must adopt an **Isolation-First Architecture**.

### 7.1 Code Isolation Strategy
*   **Abstraction Layer:** Define interfaces for search and playback (e.g., `IMusicSearchRepository`). Tempus implements `SubsonicSearchRepository`. We will create `SoulseekSearchRepository`.
*   **Package Segregation:** All slskd-related code (API interfaces, Room entities, ViewModels, Composables) must be placed in a distinct package structure (e.g., `com.eddyizm.tempus.slskd`). Avoid touching Tempus core ViewModels; instead, create parallel `SoulseekSearchViewModel` and compose it into the existing UI tree via dependency injection (Hilt).
*   **Feature Flagging:** Use a compile-time `buildConfigField` (e.g., `ENABLE_SLSKD_INTEGRATION`) to toggle the Soulseek UI. If upstream changes break the UI, the feature can be temporarily disabled without breaking the base app.

### 7.2 Git Workflow
1.  **Upstream Tracking:** Add the original repo as a remote: `git remote add upstream https://github.com/eddyizm/tempus`.
2.  **Branching Strategy:** Maintain a `main-custom` branch where all Soulseek logic lives.
3.  **Rebasing vs. Merging:** Use `git rebase upstream/main` on a regular basis. Because the custom code is isolated in its own packages and ViewModels, conflicts will likely be restricted to the Navigation Graph, `MainActivity`, or the `build.gradle` files when adding new dependencies.
4.  **Continuous Integration:** Use GitHub Actions to run unit tests on the `SlskdRepository` and `DownloadSyncWorker` logic after every rebase to ensure the integration logic hasn't been broken by upstream refactors.

### 7.3 Release Cycle
*   When Tempus releases a new version (e.g., v4.31.0), fetch the upstream changes.
*   Resolve conflicts (mostly in navigation or UI wrappers).
*   Run a localized smoke test (Search -> Download -> Verify file appears in Navidrome).
*   Build and release the custom APK.
