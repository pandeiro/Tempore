# Tempore (personal Tempus fork) — Soulseek / slskd integration

Tempus (Subsonic/Navidrome Android client), rebranded and extended with a Soulseek
search & download capability backed by a self-hosted [slskd](https://github.com/slskd/slskd)
instance. Navidrome stays the library of record.

- Architecture and phases: `docs/spec/product.claude.md` (authoritative), `docs/spec/product.qwen.md` (early draft).
- Live-verified slskd 0.26.1 facts: `slsk/SPIKE.md`; golden fixtures: `slsk/contract/fixtures/`.
- Every upstream file touched, with anchors and re-apply notes: `slsk/SEAMS.md`.
- Seam guard (budget + anchors): `slsk/tools/check-seams.sh`.

## Modules

| Path | Contents |
| --- | --- |
| `slsk/core` | `SoulseekGateway` (OkHttp/Retrofit, dedupe, TOFU pinning), Subsonic handoff client, grouping/ranking, Keystore credential store, DataStore prefs/history, fake gateway, JVM unit tests. Zero upstream imports. |
| `slsk/ui` | Search scope toggle + submit interception facade (`SlskSeams`), results screen, folder sheet, activity screen, settings activity, nav graph. `slsk_`-prefixed resources. |
| `slsk/app-glue` | `HostBridge` implementation + installer compiled INTO the app (only code allowed to import `com.eddyizm.tempus.*`), plus the recolored launcher icon + `slsk_app_name`. |
| `slsk/gradle` | `settings-hook.gradle` (S1) and `app-hook.gradle` (S2): module wiring, rebrand ids, version wrap, optional release signing. |

## Building

```bash
./gradlew assembleTempusDebug assembleDegoogledDebug          # both debug flavors
./gradlew assembleTempusRelease                              # signed when slsk/keystore/local.properties exists
./gradlew :slsk:core:testDebugUnitTest                      # gateway/ranking/store tests (MockWebServer + fixtures)
slsk/tools/check-seams.sh                                    # seam guard: <=12 files / <=150 added lines vs upstream
```

Signing: `slsk/keystore/local.properties` (gitignored) holds the release key material.
Back it up — losing the key forces an uninstall/reinstall on every device.

## Server-side contract (for library handoff)

The app cannot move files; slskd must land them where Navidrome scans:

1. `directories.downloads` → a folder **inside** Navidrome's music folder (e.g. `/music/Soulseek`).
2. Keep `incomplete` outside the music folder.
3. Disable slskd completion retention for downloads (otherwise slskd may delete library files).
4. Keep the Navidrome watcher enabled; the app also calls `startScan` as a fallback.

When a folder completes and lands outside the library, the activity screen says
*Downloaded, not visible in library yet* with a manual rescan action.

## slskd notes (0.26.1)

- `POST /transfers/downloads/batches` does NOT exist on 0.26.1 (404); the app falls back to
  `POST /transfers/downloads/{username}` with a `[{filename,size}]` body (slskd ≥0.27 ships batches).
- `GET /searches/{id}` returns `responseCount` but keeps `responses: []`; response payloads live
  only on `GET /searches/{id}/responses`.
- `id` fields must be raw GUIDs.
