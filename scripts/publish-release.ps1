param([string]$Python = 'python', [string]$Gh = 'gh')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$config = Get-Content app/build.gradle -Raw
$version = [regex]::Match($config, "versionName '([0-9.]+)'").Groups[1].Value
$code = [regex]::Match($config, 'versionCode (\d+)').Groups[1].Value
if (!$version -or !$code) { throw 'Cannot read release version' }
& $Python tests/overlay-regression.py
if ($LASTEXITCODE -ne 0) { throw 'Overlay regression failed' }
& $Python tests/host-regression.py
if ($LASTEXITCODE -ne 0) { throw 'Host regression failed' }
& $Python tests/update-download-regression.py
if ($LASTEXITCODE -ne 0) { throw 'Download regression failed' }
& "$PSScriptRoot/build-apk.ps1"
$target = "dist/MimiAutoComboLauncher-$version.apk"
$arguments = @('scripts/make-update.py', '--target', $target, '--version', $version, '--code', $code)
Get-ChildItem dist -Filter 'MimiAutoComboLauncher-*.apk' | Where-Object { $_.Name -ne "MimiAutoComboLauncher-$version.apk" } | ForEach-Object {
    $arguments += @('--base', $_.FullName)
}
& $Python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Patch generation failed' }
& $Python tests/delta-regression.py --target $target
if ($LASTEXITCODE -ne 0) { throw 'Patch tests failed' }
$manifest = Get-Content dist/update.json -Raw | ConvertFrom-Json
$assets = @($target, 'dist/update.json')
foreach ($patch in $manifest.patches) { $assets += 'dist/' + ($patch.url.Split('/')[-1]) }
$checksums = foreach ($asset in $assets) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $asset).Hash.ToLowerInvariant() + '  ' + (Split-Path $asset -Leaf)
}
$checksums | Set-Content -Encoding utf8NoBOM dist/SHA256SUMS.txt
$assets += 'dist/SHA256SUMS.txt'
$notes = "dist/release-notes-$version.md"
if (!(Test-Path $notes)) { throw "Write $notes before publishing" }
# Publish metadata and all binaries together; latest must never point at missing assets.
& $Gh release create "v$version" @assets --repo Freeze7y/MimiAutoComboLauncher --target main --title "米米自动连招启动器 v$version" --notes-file $notes --draft
if ($LASTEXITCODE -ne 0) { throw 'Draft creation/upload failed; inspect GitHub before retrying' }
& $Gh release edit "v$version" --repo Freeze7y/MimiAutoComboLauncher --draft=false --latest
if ($LASTEXITCODE -ne 0) { throw 'Draft created but not published' }
