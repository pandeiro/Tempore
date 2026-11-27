#!/usr/bin/env bash
# Tempore seam guard (claude spec §11.2):
#   1. diff budget: outside slsk/, docs/, and our workflow files — <=12 files, <=150 added lines
#   2. anchor check: every anchor in slsk/SEAMS.md still exists
set -euo pipefail
cd "$(dirname "$0")/../.."

BASE=$(git merge-base HEAD upstream/main 2>/dev/null || echo HEAD)

# 1) diff budget. README/AGENTS/PLAN/CHANGELOG are the fork documentation layer
# (declared fork-owned, see slsk/SEAMS.md rules).
read -r FILES ADDED <<<"$(git diff --numstat "$BASE" HEAD -- . ':!slsk' ':!ARCHITECTURE.md' ':!PLAN.md' ':!README.md' ':!AGENTS.md' ':!CHANGELOG.md' ':!.github/workflows/slsk_*.yml' \
  | awk '{f++; a+=$1} END {print (f+0)" "(a+0)}')"
echo "seam diff outside slsk/ + fork docs: $FILES files, $ADDED added lines"
# fork-owned rebrand-identity resources (seam R3) raised this from 12;
# launcher-adjacent recolors + shortcut icon (seam R4) raised it to 16;
# update-check rechannel (seams S8/S9) raised it to 18;
# artist-page release-type fallback (seam S10, issue #6) raised it to 20.
if [ "$FILES" -gt 20 ] || [ "$ADDED" -gt 150 ]; then
  echo "FAIL: seam budget exceeded (max 20 files / 150 added lines)" >&2
  git diff --numstat "$BASE" HEAD -- . ':!slsk' ':!ARCHITECTURE.md' ':!PLAN.md' ':!README.md' ':!AGENTS.md' ':!CHANGELOG.md' ':!.github/workflows/slsk_*.yml'
  exit 1
fi

# 2) anchors
check() { # file, grep-pattern-anchor
  local file="$1" anchor="$2"
  if ! echo "path: $file" | grep -q . || [ -z "$anchor" ]; then return 0; fi
}
anchor_check() {
  if [ ! -f "$1" ] || ! grep -qF "$2" "$1"; then
    echo "FAIL: seam anchor missing in $1: $2" >&2
    exit 1
  fi
}
anchor_check settings.gradle "apply from: 'slsk/gradle/settings-hook.gradle'"
anchor_check app/build.gradle "apply from: '../slsk/gradle/app-hook.gradle'"
anchor_check app/src/main/java/com/eddyizm/tempus/ui/fragment/SearchFragment.java "SlskSeams.attachSearchScreen(this, bind.searchBar, bind.searchView, bind.searchViewSuggestionContainer, bind.emptyResponseContainer, this::search);"
anchor_check app/src/main/java/com/eddyizm/tempus/ui/fragment/SearchFragment.java "if (SlskSeams.shouldAutoShow(this)) inputFocus();"
anchor_check app/src/main/java/com/eddyizm/tempus/ui/fragment/SearchFragment.java "if (SlskSeams.ownsSuggestions(this)) return;"
anchor_check app/src/main/java/com/eddyizm/tempus/ui/fragment/SearchFragment.java "SlskSeams.onSearchSubmitted(this, query)"
anchor_check app/src/main/res/layout/fragment_search.xml '<include layout="@layout/slsk_view_search_cta" />'
anchor_check app/src/main/res/xml/global_preferences.xml 'android:key="slsk_settings_entry"'
anchor_check app/src/main/java/com/eddyizm/tempus/ui/fragment/SettingsContainerFragment.java 'com.github.pandeiro.tempore.slsk.ui.SlskSettingsActivity'
anchor_check app/src/main/res/navigation/nav_graph.xml '<include app:graph="@navigation/slsk_nav_graph" />'
anchor_check app/src/main/java/com/eddyizm/tempus/App.java 'com.github.pandeiro.tempore.glue.SlskGlueInstaller.install(context);'
anchor_check app/src/main/AndroidManifest.xml 'android:icon="@mipmap/slsk_launcher"'
anchor_check app/src/main/AndroidManifest.xml 'android:enableOnBackInvokedCallback="false"'
anchor_check app/src/main/AndroidManifest.xml 'android:label="@string/slsk_app_name"'
anchor_check app/src/main/res/drawable/ic_toolbar_tempo.xml '#FFC98352'
anchor_check app/src/main/res/drawable/ic_splash_logo.xml '#FFC98352'
anchor_check app/src/main/res/drawable/ic_launcher_background.xml '#FFC98352'
anchor_check app/src/main/res/drawable/ic_launcher_foreground.xml '#FFC98352'
anchor_check app/src/main/java/com/eddyizm/tempus/ui/activity/MainActivity.java 'R.mipmap.slsk_launcher'
anchor_check app/src/main/java/com/eddyizm/tempus/github/Github.java 'return "https://api.github.com/";'
anchor_check app/src/main/java/com/eddyizm/tempus/util/Preferences.kt 'private const val GITHUB_UPDATE_CHECK = "github_update_check"'
anchor_check app/src/main/java/com/eddyizm/tempus/viewmodel/ArtistPageViewModel.java 'absence of metadata defaults to "album"'
anchor_check .github/workflows/github_release.yml "contains(github.ref_name, 'tempore')" # seam S11: train tags never re-trigger the legacy workflow

echo "OK: seam budget + anchors pass"
