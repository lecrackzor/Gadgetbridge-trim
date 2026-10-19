# Merge Freeyourgadget upstream/master into the current branch and verify the trim build.
# Usage (from Gadgetbridge repo root):
#   .\scripts\sync-upstream.ps1
#   .\scripts\sync-upstream.ps1 -Push

param(
    [switch]$Push
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this from the Gadgetbridge repo root (gradlew.bat not found)."
}

$jdk = Get-ChildItem "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" } |
    Select-Object -First 1 -ExpandProperty FullName
if ($jdk) {
    $env:JAVA_HOME = $jdk
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

Write-Host "Fetching upstream..."
git remote get-url upstream 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    git remote add upstream https://codeberg.org/Freeyourgadget/Gadgetbridge.git
}
git fetch upstream master

$branch = (git branch --show-current).Trim()
Write-Host "Merging upstream/master into $branch ..."
git merge --no-edit upstream/master
if ($LASTEXITCODE -ne 0) {
    throw "Merge had conflicts. Resolve them (keep trim workout code), then re-run the build."
}

Write-Host "Building :app:assembleMainlineDebug ..."
.\gradlew.bat :app:assembleMainlineDebug --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "Build failed after upstream merge. Fix compile errors before pushing."
}

Write-Host "Upstream sync OK on $branch."
if ($Push) {
    git push origin HEAD
    Write-Host "Pushed to origin."
} else {
    Write-Host "Skipped push (pass -Push to publish)."
}
