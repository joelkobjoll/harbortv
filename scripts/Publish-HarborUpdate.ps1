$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug'
$release = (Get-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Raw |
    ConvertFrom-Json).elements | Select-Object -First 1
$apk = Join-Path $output $release.outputFile
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$directory = Join-Path $PSScriptRoot 'update-feed'
New-Item -Path $directory -ItemType Directory -Force | Out-Null
$fileName = "HarborTV-$($release.versionCode)-$($hash.Substring(0, 12)).apk"
$destination = Join-Path $directory $fileName
if (!(Test-Path -LiteralPath $destination)) {
    Copy-Item -LiteralPath $apk -Destination $destination
}
if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash) {
    throw 'Published APK checksum mismatch.'
}

function Publish-StagedFile($staged, $target) {
    if (Test-Path -LiteralPath $target) {
        $backup = "$target.bak"
        [System.IO.File]::Replace($staged, $target, $backup)
        Remove-Item -LiteralPath $backup
    } else {
        [System.IO.File]::Move($staged, $target)
    }
}

$installer = Join-Path $directory 'HarborTV.apk'
if (!(Test-Path -LiteralPath $installer) -or
    (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash -ne $hash) {
    $stagedInstaller = "$installer.tmp"
    Copy-Item -LiteralPath $destination -Destination $stagedInstaller
    Publish-StagedFile $stagedInstaller $installer
}

$manifest = Join-Path $directory 'update.json'
$temporary = Join-Path $directory 'update.json.tmp'
$json = [ordered]@{
    versionCode = $release.versionCode
    versionName = $release.versionName
    minSdk = 22
    apk = $fileName
    sha256 = $hash
} | ConvertTo-Json
[System.IO.File]::WriteAllText($temporary, $json)
Publish-StagedFile $temporary $manifest
Get-Content -LiteralPath $manifest
