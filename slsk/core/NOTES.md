# slsk/core implementation notes (deviations + open questions)

Contract source: docs/spec/product.claude.md §6 + slsk/SPIKE.md (live-probed slskd 0.26.1.0 — authoritative), fixtures in slsk/contract/fixtures (also vendored into `src/test/resources/fixtures/` for the JVM tests).

## Deviations / judgement calls

1. **`SlskdConfig` gains `@Serializable`** (extension, not a break) — the encrypted store needs to
   persist the whole config as one JSON blob. Signature and semantics unchanged.
2. **`StateTokens.failed` is ANY-of, not ALL-of.** Composite slskd state strings carry exactly one
   failure token (`Completed, Aborted` etc.); the original all-of implementation could never fire,
   and the acceptance criteria itself requires `Completed, Aborted -> failed`. `succeeded` stays
   all-of (`Completed`+`Succeeded`) which is correct there.
3. **`transfers()` is a single-snapshot flow.** Per contract, "the gateway just fetches snapshot";
   the 3 s cadence belongs to the UI layer, which re-collects. Documented on the override.
4. **Batches response parsing is tolerant by necessity.** slskd 0.26.1 returns an array whose
   per-item field names are not pinned by SPIKE.md; failures are read from `success == false /
   error / exception / message` spellings and any 2xx with no readable failure counts as accepted.
5. **Search POST uses `?requestTimeout=` default 90 s** fired on a worker while the poll loop
   starts immediately; abandoned collectors PUT-stop then DELETE inside `NonCancellable` so the
   cleanup survives cancellation. Completed searches are never deleted.
6. **TOFU pinning** accepts (a) any chain member matching the pinned SHA-256 or (b) ordinary system
   trust. Hostname verification is scoped to this slskd client only (LAN IPs are legitimate);
   identity is enforced by the pin. Never a global trust-all.
7. **Navidrome/Subsonic token-auth fallback:** when `useTokenAuth == false`, the client sends
   plaintext `p` params (hex-style is a later hardening); Subsonic version pinned to 1.15.0.
   `startScan`/`getScanStatus`/`search3` errors map to null / false / `ScanStatus.Unknown`, never crashes.
8. **`folderContents` fallback:** remembers the last search hits per (username, directory) in
   memory; the `POST /users/{username}/directory` endpoint is tried first and its failure (404
   unknown user) falls through to those remembered files (locked ones excluded).
9. **`KeystoreCredentialStore.open` throws a sealed `StoreError` on Keystore failures**; decryption
   failures surface as `StoreError.Decrypt` (from `load()`), the blob is removed only when
   malformed. Session falls back to the demo gateway and surfaces `lastStoreError()`.

## Fixture observations worth remembering
- Fixture file entries can carry `extension: ""` (derivable from the filename) and an explicit
  `bitRate` for some `@320` files; live responses (SPIKE.md) have NO `bitRate`, so the mapper
  derives kbps from size/length (evaluated at `Music\\...\\mp3 @320` -> 323).
- `lockedFiles` is its own array in responses; locked entries are mapped with `isLocked = true`
  and remain visible.

## Open questions
- Exact batches P200/207 response item field names on 0.26.1 (needs live acceptance enqueue;
  parser is tolerant to the observed spellings).
- Whether slskd 0.26.1's `PUT /api/v0/searches/{id}` exists (older versions 404; handled).
- `clearCompleted` on "nothing completed" may answer 404/503; tolerated silently (no result contract).
