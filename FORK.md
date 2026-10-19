# Gadgetbridge-trim fork

Personal fork of [Freeyourgadget/Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) with **in-app workout trim** (raw `.bin` + DB stay in sync).

## Remotes

| Remote | URL |
|--------|-----|
| `origin` | https://github.com/lecrackzor/Gadgetbridge-trim.git |
| `upstream` | https://codeberg.org/Freeyourgadget/Gadgetbridge.git |

```bash
git remote -v
# origin    …/lecrackzor/Gadgetbridge-trim.git
# upstream  …/Freeyourgadget/Gadgetbridge.git
```

## Trim feature

Sports Activities → activity detail → Edit (pencil) → **Trim workout**.

Save path: backup `.bin.bak` → write trimmed details → patch summary times / Zepp raw summary / `summaryData` → refresh linked GPX → update DB (restore `.bin` if DB update fails).

### Installing over F-Droid / Droidify

Debug builds use the Android **debug** key. You must uninstall the store build first (export your database first, then import after installing the debug APK).

## Keeping up with upstream

### Automatic (GitHub Actions)

Workflow **Sync upstream** (`.github/workflows/upstream-sync.yml`):

- Runs weekly (Monday) and on demand (`Actions` → Sync upstream → Run workflow)
- Fetches Codeberg `master`, merges into our `master`
- Builds `:app:assembleMainlineDebug` so trim still compiles
- Pushes only if merge + build succeed; otherwise opens a GitHub issue labeled `upstream-sync`

CI (`.github/workflows/ci.yml`) builds mainline debug on every push/PR to `master`.

### Manual (local)

From the `Gadgetbridge` directory (JDK 21 recommended):

```powershell
.\scripts\sync-upstream.ps1          # merge + build
.\scripts\sync-upstream.ps1 -Push    # also push origin
```

Or by hand:

```bash
git fetch upstream
git checkout master
git merge upstream/master
./gradlew :app:assembleMainlineDebug
git push origin master
```

If `WorkoutDetailsFragment`, menus, or activity-summary parsers conflict, keep the trim menu entry and `WorkoutTrim*` classes.

## Build

```bash
./gradlew :app:assembleMainlineDebug
# APK: app/build/outputs/apk/mainline/debug/app-mainline-debug.apk
```
