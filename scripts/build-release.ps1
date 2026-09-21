# build-release.ps1 — Build release AAB & APK with automatic Git versioning
# Usage: .\build-release.ps1
# Usage with custom message: .\build-release.ps1 -Message "fix: resolved crash on startup"

param(
    [string]$Message = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Auto-bump versionCode in build.gradle.kts
$gradleFile = "app\build.gradle.kts"
$content = Get-Content $gradleFile -Raw
if ($content -match 'versionCode\s*=\s*(\d+)') {
    $oldVersionCode = [int]$Matches[1]
    $newVersionCode = $oldVersionCode + 1
    $content = $content -replace "versionCode\s*=\s*$oldVersionCode", "versionCode = $newVersionCode"
    Set-Content $gradleFile -Value $content
    Write-Host "Auto-bumped versionCode from $oldVersionCode to $newVersionCode" -ForegroundColor Yellow
}

# Extract version info from build.gradle.kts
$content = Get-Content $gradleFile -Raw
$versionCode = if ($content -match 'versionCode\s*=\s*(\d+)') { $Matches[1] } else { "unknown" }
$versionName = if ($content -match 'versionName\s*=\s*"([^"]+)"') { $Matches[1] } else { "unknown" }

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  ClearSpace Release Builder" -ForegroundColor Cyan
Write-Host "  Version: $versionName (code: $versionCode)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Stage all changes
Write-Host "[1/5] Committing changes to Git..." -ForegroundColor Yellow
git add -A

# Check if there are changes to commit
$status = git status --porcelain
if ($status) {
    if ($Message -eq "") {
        $Message = "release: v$versionName (versionCode $versionCode)"
    }
    git commit -m $Message
    Write-Host "  Committed: $Message" -ForegroundColor Green
} else {
    Write-Host "  No changes to commit, skipping." -ForegroundColor DarkGray
}

# Build the AAB & APK
Write-Host ""
Write-Host "[2/5] Building release AAB and APK..." -ForegroundColor Yellow
.\gradlew.bat bundleRelease assembleRelease

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "BUILD FAILED!" -ForegroundColor Red
    exit 1
}

# Archive the AAB and APK
Write-Host ""
Write-Host "[3/5] Archiving AAB and APK..." -ForegroundColor Yellow
$aabSource = "app\build\outputs\bundle\release\app-release.aab"
$apkSource = (Get-ChildItem "app\build\outputs\apk\release\*.apk" | Select-Object -First 1).FullName
$releasesDir = "releases"
if (-not (Test-Path $releasesDir)) {
    New-Item -ItemType Directory -Path $releasesDir | Out-Null
}

$timestamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$aabArchiveName = "clearspace-v${versionName}-code${versionCode}-${timestamp}.aab"
$apkArchiveName = "clearspace-v${versionName}-code${versionCode}-${timestamp}.apk"
$aabArchivePath = Join-Path $releasesDir $aabArchiveName
$apkArchivePath = Join-Path $releasesDir $apkArchiveName

Copy-Item $aabSource $aabArchivePath
Copy-Item $apkSource $apkArchivePath

$aabSize = [math]::Round((Get-Item $aabArchivePath).Length / 1MB, 1)
$apkSize = [math]::Round((Get-Item $apkArchivePath).Length / 1MB, 1)

Write-Host "  Saved AAB: $aabArchivePath ($aabSize MB)" -ForegroundColor Green
Write-Host "  Saved APK: $apkArchivePath ($apkSize MB)" -ForegroundColor Green

# Log to HISTORY.md
Write-Host ""
Write-Host "[4/5] Updating Release History..." -ForegroundColor Yellow
$historyFile = Join-Path $releasesDir "HISTORY.md"
if (-not (Test-Path $historyFile)) {
    Set-Content -Path $historyFile -Value "# ClearSpace Release History`n"
}
$historyEntry = "- **v${versionName}** (Code: ${versionCode}) - Built on $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n  - AAB: ${aabArchiveName} (${aabSize} MB)`n  - APK: ${apkArchiveName} (${apkSize} MB)"
Add-Content -Path $historyFile -Value $historyEntry
Write-Host "  Logged release to $historyFile" -ForegroundColor Green

# Count total archived releases
$totalAab = @(Get-ChildItem $releasesDir -Filter "*.aab").Count
$totalApk = @(Get-ChildItem $releasesDir -Filter "*.apk").Count

# Tag the release
Write-Host ""
Write-Host "[5/5] Tagging release..." -ForegroundColor Yellow
$tag = "v$versionName-$versionCode"
$existingTag = git tag -l $tag
if (-not $existingTag) {
    git tag -a $tag -m "Release $versionName (versionCode $versionCode)"
    Write-Host "  Tagged: $tag" -ForegroundColor Green
} else {
    Write-Host "  Tag $tag already exists, skipping." -ForegroundColor DarkGray
}

# Output result
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "  Version: $versionName (code: $versionCode)" -ForegroundColor Green
Write-Host "  Git tag: $tag" -ForegroundColor Green
Write-Host "  Releases dir: $releasesDir/" -ForegroundColor Green
Write-Host "    - $totalAab AAB files" -ForegroundColor Green
Write-Host "    - $totalApk APK files" -ForegroundColor Green
Write-Host "  History log: $historyFile" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
