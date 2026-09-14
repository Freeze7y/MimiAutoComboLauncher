param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$bt = Join-Path $root 'tools\sdk-build-tools\android-16'
$platform = Join-Path $root 'tools\sdk-platform\android-36\android.jar'
if (!(Test-Path $platform) -or !(Test-Path "$bt\aapt2.exe")) { throw 'Android SDK 36 tools missing; see docs/BUILDING.md' }
$build = 'build\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
New-Item -ItemType Directory -Force $build,"$build\generated","$build\classes","$build\dex",'dist','signing' | Out-Null
function Check([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step failed ($LASTEXITCODE)" } }
& "$bt\aapt2.exe" compile --dir app\src\main\res -o "$build\resources.zip"
Check 'resource compile'
[xml]$manifest = Get-Content app\src\main\AndroidManifest.xml -Raw
$manifest.DocumentElement.SetAttribute('package', 'dev.local.nativemacrohelper')
$manifest.Save((Join-Path $root "$build\AndroidManifest.xml"))
& "$bt\aapt2.exe" link -o "$build\base.apk" --manifest "$build\AndroidManifest.xml" -I $platform --java "$build\generated" --min-sdk-version 29 --target-sdk-version 36 --version-code 14 --version-name 1.4.2 --proguard "$build\resources.pro" "$build\resources.zip"
Check 'resource link'
$sources = @(Get-ChildItem app\src\main\java,"$build\generated" -Recurse -Filter '*.java' | ForEach-Object { '"' + [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\','/') + '"' })
$sources | Set-Content -Encoding utf8NoBOM "$build\sources.txt"
& javac -encoding UTF-8 -source 8 -target 8 -classpath $platform -d "$build\classes" "@$build\sources.txt"
Check 'javac'
& jar cf "$build\classes.jar" -C "$build\classes" .
Check 'jar'
'-keep public class dev.local.nativemacrohelper.*Activity { *; }' | Set-Content "$build\keep.pro"
& java -cp "$bt\lib\d8.jar" com.android.tools.r8.R8 --release --min-api 29 --lib $platform --pg-conf "$build\keep.pro" --pg-conf "$build\resources.pro" --pg-map-output "$build\mapping.txt" --output "$build\dex" "$build\classes.jar"
Check 'R8'
Copy-Item "$build\base.apk" "$build\unsigned.apk"
& jar uf "$build\unsigned.apk" -C "$build\dex" classes.dex
Check 'dex packaging'
& "$bt\zipalign.exe" -f -p 4 "$build\unsigned.apk" "$build\aligned.apk"
Check 'zipalign'
$key = Join-Path $root 'signing\local-release.jks'
$passwordFile = Join-Path $root 'signing\password.txt'
if (!(Test-Path $key)) {
    if (Test-Path $passwordFile) { throw 'Signing password exists without key; inspect signing directory before retrying.' }
    [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) | Set-Content -Encoding ascii $passwordFile
    & keytool -genkeypair -keystore $key -storepass:file $passwordFile -keypass:file $passwordFile -alias release -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Native Macro Helper Local Release' -noprompt
    Check 'key generation'
}
$apk = Join-Path $root 'dist\MimiAutoComboLauncher-1.4.2.apk'
& java -jar "$bt\lib\apksigner.jar" sign --ks $key --ks-key-alias release --ks-pass "file:$passwordFile" --out $apk "$build\aligned.apk"
Check 'sign'
& java -jar "$bt\lib\apksigner.jar" verify --verbose --print-certs $apk | Tee-Object dist\signature.txt
Check 'signature verification'
& "$bt\zipalign.exe" -c -p 4 $apk
Check 'alignment verification'
& "$bt\aapt2.exe" dump badging $apk | Set-Content -Encoding utf8 dist\badging.txt
Check 'manifest verification'
Get-FileHash $apk -Algorithm SHA256 | Format-List | Out-File dist\sha256.txt
Write-Output "APK: $apk"
