param(
    [string]$Version = "1.0.0",
    [string]$GoogleClientId = $env:NXSYNC_GOOGLE_CLIENT_ID,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist"
$stage = Join-Path $dist "NXSync-$Version"

if (-not $SkipBuild) {
    $go = Get-Command go -ErrorAction SilentlyContinue
    if (-not $go) {
        $bundled = Join-Path $root ".tools\go-toolchain\go\bin\go.exe"
        if (Test-Path -LiteralPath $bundled) {
            $go = Get-Item -LiteralPath $bundled
        } else {
            throw "Go was not found."
        }
    }
    $env:CGO_ENABLED = "0"
    $env:GOCACHE = Join-Path $root ".tools\gocache"
    $env:GOMODCACHE = Join-Path $root ".tools\gomodcache"
    $flags = "-s -w"
    if ($GoogleClientId) {
        $flags += " -X main.googleClientID=$GoogleClientId"
    }
    Push-Location $root
    try {
        & $go.FullName test "./..."
        if ($LASTEXITCODE -ne 0) { throw "Go tests failed." }
    } finally {
        Pop-Location
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $root "bin") | Out-Null
    Push-Location $root
    try {
        & $go.FullName build -tags production -trimpath -ldflags $flags `
            -o (Join-Path $root "bin\nxsync-desktop.exe") `
            "./cmd/nxsync-desktop"
        if ($LASTEXITCODE -ne 0) { throw "Desktop build failed." }
    } finally {
        Pop-Location
    }

    if (-not $env:JAVA_HOME) {
        $studioJbr = "C:\Program Files\Android\Android Studio\jbr"
        if (Test-Path -LiteralPath $studioJbr) { $env:JAVA_HOME = $studioJbr }
    }
    if (-not $env:ANDROID_HOME) {
        $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path -LiteralPath $sdk) { $env:ANDROID_HOME = $sdk }
    }
    Push-Location (Join-Path $root "android")
    try {
        & ".\gradlew.bat" assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Android build failed." }
    } finally {
        Pop-Location
    }
}

$required = @(
    (Join-Path $root "bin\nxsync-desktop.exe"),
    (Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"),
    (Join-Path $root "switch\sys-savesync\sys-savesync.nsp"),
    (Join-Path $root "switch\overlay\savesync.ovl")
)
foreach ($file in $required) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "Missing build output: $file"
    }
}

if (Test-Path -LiteralPath $stage) {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
$switchRoot = Join-Path $stage "Switch SD Card"
$contentRoot = Join-Path $switchRoot "atmosphere\contents\42000000004E5853"
New-Item -ItemType Directory -Force -Path `
    (Join-Path $stage "Windows"), `
    (Join-Path $stage "Android"), `
    (Join-Path $contentRoot "flags"), `
    (Join-Path $switchRoot "config\savesync"), `
    (Join-Path $switchRoot "switch\.overlays") | Out-Null

Copy-Item -LiteralPath $required[0] -Destination (Join-Path $stage "Windows\NXSync.exe")
Copy-Item -LiteralPath $required[1] -Destination (Join-Path $stage "Android\NXSync-debug.apk")
Copy-Item -LiteralPath $required[2] -Destination (Join-Path $contentRoot "exefs.nsp")
Copy-Item -LiteralPath $required[3] -Destination (Join-Path $switchRoot "switch\.overlays\savesync.ovl")
Copy-Item -LiteralPath (Join-Path $root "switch\sys-savesync\config.example.json") `
    -Destination (Join-Path $switchRoot "config\savesync\config.json")
New-Item -ItemType File -Force -Path (Join-Path $contentRoot "flags\boot2.flag") | Out-Null
Copy-Item -LiteralPath (Join-Path $root "README.md") -Destination $stage

$zip = Join-Path $dist "NXSync-$Version.zip"
if (Test-Path -LiteralPath $zip) {
    Remove-Item -LiteralPath $zip -Force
}
Compress-Archive -LiteralPath $stage -DestinationPath $zip -CompressionLevel Optimal

$hashes = Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($stage.Length + 1)
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $relative"
}
$hashes | Set-Content -LiteralPath (Join-Path $dist "SHA256SUMS.txt") -Encoding utf8
Write-Host "Packaged $zip"
