# Gadgetbridge-trim fork notes

This repository is a public fork of [Freeyourgadget/Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) with an **in-app workout trim** feature. See the [README](README.md) for screenshots and usage.

## Remotes

| Remote | URL |
|--------|-----|
| `origin` | this GitHub fork |
| `upstream` | https://codeberg.org/Freeyourgadget/Gadgetbridge.git |

```bash
git remote add upstream https://codeberg.org/Freeyourgadget/Gadgetbridge.git
```

## Keeping up with upstream

### Automatic (GitHub Actions)

- **Sync upstream** (`.github/workflows/upstream-sync.yml`) — weekly + manual: merge Codeberg `master`, build mainline debug, push only if the build succeeds
- **CI** (`.github/workflows/ci.yml`) — builds on push/PR to `master`

### Manual

```powershell
.\scripts\sync-upstream.ps1
.\scripts\sync-upstream.ps1 -Push
```

Or:

```bash
git fetch upstream
git checkout master
git merge upstream/master
./gradlew :app:assembleMainlineDebug
git push origin master
```

If `WorkoutDetailsFragment`, menus, or summary parsers conflict, keep the trim menu entry and `WorkoutTrim*` classes.

## Privacy

Do not commit exports, databases, `.bin` workouts, logs, or `local.properties`. Those paths are gitignored where applicable.
