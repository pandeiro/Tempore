# SEAMS.md — every upstream file touched by Tempore, with anchors and re-application notes.
# Re-apply after upstream sync; guard script: slsk/tools/check-seams.sh

| # | File | Anchor (must still exist) | Change |
| --- | --- | --- | --- |
| S1 | `settings.gradle` | `rootProject.name = "Tempus"` | +1 line: `apply from: 'slsk/gradle/settings-hook.gradle'` |
| S2 | `app/build.gradle` | trailing `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } } }` | +1 line: `apply from: '../slsk/gradle/app-hook.gradle'` (plain string — GString apply triggers a Groovy DSL parse bug in Gradle 9.8) |
| S3 | `app/src/main/java/com/eddyizm/tempus/ui/fragment/SearchFragment.java` | `initSearchView();` / `public void search(String query) {` | +1 import `com.github.pandeiro.tempore.slsk.ui.SlskSeams`; +1 line `SlskSeams.attachScopeToggle(this, bind.emptyResponseContainer);` after `initSearchView();`; +4 lines scope branch at top of `search()` |
| S4 | `app/src/main/res/layout/fragment_search.xml` | `android:text="@string/search_description_empty_response"` inside `empty_response_container` | +1 line: `<include layout="@layout/slsk_view_search_cta" />` before the container's `</LinearLayout>` |
| S5 | `app/src/main/res/xml/global_preferences.xml` | `<com.eddyizm.tempus.util.ClickablePreferenceCategory app:title="@string/settings_about_title">` | +7 lines: new Soulseek `ClickablePreferenceCategory` (1 `Preference`, key `slsk_settings_entry`) before the About category |
| S5 | `app/src/main/java/com/eddyizm/tempus/ui/fragment/SettingsContainerFragment.java` | `SwitchPreference downloadWifiOnlyPreference = findPreference("download_wifi_only");` | +6 lines: click listener on `slsk_settings_entry` → `SlskSettingsActivity` |
| S6 | `app/src/main/res/navigation/nav_graph.xml` | final `</navigation>` | +1 line before it: `<include app:graph="@navigation/slsk_nav_graph" />` |
| S7 | `app/src/main/java/com/eddyizm/tempus/App.java` | `ClientCertManager.setupSslSocketFactory(context);` | +1 line after it: `com.github.pandeiro.tempore.glue.SlskGlueInstaller.install(context);` |
| R1 | `app/src/main/AndroidManifest.xml` | `<application ...` block | label `@string/app_name` → `@string/slsk_app_name`; icon/roundIcon `@mipmap/ic_launcher` → `@mipmap/slsk_launcher` (Tempore rebrand) |
| R2 | `app/src/main/res/values/strings.xml` | `<string name="app_name">Tempus</string>` | default-value rename to `Tempore` (default locale = in-app toolbar title; per-locale `values-XX` copies intentionally untouched — non-English UIs still show upstream name) |

Rules (claude spec §11.2, as amended by the Tempore rename):
- New code lives under `slsk/` (modules `:slsk:core`, `:slsk:ui`, glue sources `slsk/app-glue`, hooks `slsk/gradle/*`, own catalog `slsk/gradle/slsk.versions.toml`, this file, `slsk/tools/`).
- No other upstream file may be edited. Rebrand seams are R1; the launcher label/icon point at NEW prefixed resources (`slsk_app_name`, `slsk_launcher`) so per-locale `app_name` copies stay untouched.
- Budget: outside `slsk/`, `docs/`, and `.github/workflows/slsk_*.yml`: ≤12 files, ≤150 added lines. Guard script enforces.
